package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, RateLimitError, ValidationError }
import org.llm4s.error.ThrowableOps._
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.GeminiConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.streaming._
import org.llm4s.model.TransformationResult
import org.llm4s.toolapi.ToolFunction
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.util.UUID
import scala.util.Try

/**
 * LLMClient implementation for Google Gemini models.
 *
 * Provides access to Gemini 2.0, 1.5 Pro, 1.5 Flash and other Gemini models
 * via Google's Generative AI API.
 *
 * == Supported Features ==
 *  - Chat completions
 *  - Streaming responses
 *  - Tool/function calling
 *  - Large context windows (up to 1M+ tokens)
 *
 * == Configuration ==
 * {{{
 * export LLM_MODEL=gemini/gemini-2.0-flash
 * export GOOGLE_API_KEY=your-api-key
 * }}}
 *
 * == API Format ==
 *
 * Gemini uses a different message format than OpenAI:
 * - Messages have `role` (user/model) and `parts` (array of content)
 * - System instructions are sent separately
 * - Tool calls use `functionDeclarations` format
 *
 * @param config Gemini configuration with API key, model, and base URL
 *
 * @see [[org.llm4s.llmconnect.config.GeminiConfig]] for configuration options
 */
class GeminiClient(config: GeminiConfig) extends LLMClient {
  private val logger = LoggerFactory.getLogger(getClass)

  override def complete(
    conversation: Conversation,
    options: CompletionOptions
  ): Result[Completion] =
    TransformationResult.transform(config.model, options, conversation.messages, dropUnsupported = true).flatMap {
      transformed =>
        val transformedConversation = conversation.copy(messages = transformed.messages)
        val requestBody             = buildRequestBody(transformedConversation, transformed.options)
        val url                     = s"${config.baseUrl}/models/${config.model}:generateContent?key=${config.apiKey}"

        logger.debug(s"[Gemini] Sending request to $url")
        logger.debug(s"[Gemini] Request body: ${requestBody.render()}")

        val attempt = Try {
          val response = requests.post(
            url,
            data = requestBody.render(),
            headers = Map("Content-Type" -> "application/json"),
            readTimeout = 120000,
            connectTimeout = 30000
          )

          if (response.statusCode >= 200 && response.statusCode < 300) {
            parseCompletionResponse(response.text())
          } else {
            handleErrorResponse(response.statusCode, response.text())
          }
        }.toEither.left
          .map(e => e.toLLMError)
          .flatten

        attempt
    }

  override def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions(),
    onChunk: StreamedChunk => Unit
  ): Result[Completion] =
    TransformationResult.transform(config.model, options, conversation.messages, dropUnsupported = true).flatMap {
      transformed =>
        val transformedConversation = conversation.copy(messages = transformed.messages)
        val requestBody             = buildRequestBody(transformedConversation, transformed.options)
        val url = s"${config.baseUrl}/models/${config.model}:streamGenerateContent?key=${config.apiKey}&alt=sse"

        logger.debug(s"[Gemini] Starting stream to $url")

        val accumulator = StreamingAccumulator.create()
        val messageId   = UUID.randomUUID().toString

        val attempt = Try {
          val response = requests.post(
            url,
            data = requestBody.render(),
            headers = Map("Content-Type" -> "application/json"),
            readTimeout = 120000,
            connectTimeout = 30000
          )

          if (response.statusCode >= 200 && response.statusCode < 300) {
            // Parse SSE stream
            val lines = response.text().split("\n")
            lines.foreach { line =>
              if (line.startsWith("data: ")) {
                val jsonStr = line.stripPrefix("data: ").trim
                if (jsonStr.nonEmpty && jsonStr != "[DONE]") {
                  Try(ujson.read(jsonStr)).foreach { json =>
                    parseStreamChunk(json, messageId).foreach { chunk =>
                      accumulator.addChunk(chunk)
                      onChunk(chunk)
                    }
                  }
                }
              }
            }
            Right(())
          } else {
            handleErrorResponse(response.statusCode, response.text())
          }
        }.toEither.left
          .map(e => e.toLLMError)
          .flatten

        attempt.flatMap(_ => accumulator.toCompletion.map(c => c.copy(model = config.model)))
    }

  override def getContextWindow(): Int = config.contextWindow

  override def getReserveCompletion(): Int = config.reserveCompletion

  /**
   * Build the request body for Gemini API.
   *
   * @param conversation The conversation to convert
   * @param options Completion options
   */
  private def buildRequestBody(
    conversation: Conversation,
    options: CompletionOptions
  ): ujson.Value = {
    val contents    = scala.collection.mutable.ArrayBuffer[ujson.Value]()
    var systemInstr = Option.empty[String]

    // Process messages - Gemini uses "user" and "model" roles
    conversation.messages.foreach {
      case SystemMessage(content) =>
        // Gemini handles system messages via systemInstruction
        systemInstr = Some(content)

      case UserMessage(content) =>
        contents += ujson.Obj(
          "role"  -> "user",
          "parts" -> ujson.Arr(ujson.Obj("text" -> content))
        )

      case AssistantMessage(contentOpt, toolCalls) =>
        if (toolCalls.nonEmpty) {
          // Assistant message with tool calls
          val parts = scala.collection.mutable.ArrayBuffer[ujson.Value]()
          contentOpt.foreach(c => parts += ujson.Obj("text" -> c))
          toolCalls.foreach { tc =>
            parts += ujson.Obj(
              "functionCall" -> ujson.Obj(
                "name" -> tc.name,
                "args" -> tc.arguments
              )
            )
          }
          contents += ujson.Obj("role" -> "model", "parts" -> ujson.Arr(parts.toSeq: _*))
        } else {
          contentOpt.foreach { content =>
            contents += ujson.Obj(
              "role"  -> "model",
              "parts" -> ujson.Arr(ujson.Obj("text" -> content))
            )
          }
        }

      case ToolMessage(content, toolCallId) =>
        // Gemini uses functionResponse for tool results
        contents += ujson.Obj(
          "role" -> "user",
          "parts" -> ujson.Arr(
            ujson.Obj(
              "functionResponse" -> ujson.Obj(
                "name"     -> toolCallId,
                "response" -> ujson.Obj("result" -> content)
              )
            )
          )
        )
    }

    // Build generation config
    val generationConfig = ujson.Obj(
      "temperature" -> options.temperature,
      "topP"        -> options.topP
    )
    options.maxTokens.foreach(mt => generationConfig("maxOutputTokens") = mt)

    // Build request
    val request = ujson.Obj(
      "contents"         -> ujson.Arr(contents.toSeq: _*),
      "generationConfig" -> generationConfig
    )

    // Add system instruction if present
    systemInstr.foreach { sysContent =>
      request("systemInstruction") = ujson.Obj(
        "parts" -> ujson.Arr(ujson.Obj("text" -> sysContent))
      )
    }

    // Add tools if specified
    if (options.tools.nonEmpty) {
      val functionDeclarations = options.tools.map(convertToolToGeminiFormat)
      request("tools") = ujson.Arr(
        ujson.Obj("functionDeclarations" -> ujson.Arr(functionDeclarations: _*))
      )
    }

    request
  }

  /**
   * Convert a tool to Gemini's function declaration format.
   */
  private def convertToolToGeminiFormat(tool: ToolFunction[_, _]): ujson.Value = {
    val schema = ujson.read(tool.schema.toJsonSchema(false).render())

    ujson.Obj(
      "name"        -> tool.name,
      "description" -> tool.description,
      "parameters"  -> schema
    )
  }

  /**
   * Parse a non-streaming completion response.
   */
  private def parseCompletionResponse(responseText: String): Result[Completion] =
    Try {
      val json       = ujson.read(responseText)
      val candidates = json("candidates").arr

      if (candidates.isEmpty) {
        Left(ValidationError("response", "No candidates in Gemini response"))
      } else {
        val candidate = candidates.head
        val content   = candidate("content")
        val parts     = content("parts").arr

        // Extract text content
        val textContent = parts
          .filter(p => p.obj.contains("text"))
          .map(_("text").str)
          .mkString

        // Extract tool calls
        val toolCalls = parts
          .filter(p => p.obj.contains("functionCall"))
          .map { p =>
            val fc = p("functionCall")
            ToolCall(
              id = UUID.randomUUID().toString, // Gemini doesn't provide tool call IDs
              name = fc("name").str,
              arguments = fc("args")
            )
          }
          .toSeq

        // Extract usage
        val usageOpt = Try {
          val usage = json("usageMetadata")
          TokenUsage(
            promptTokens = usage("promptTokenCount").num.toInt,
            completionTokens = usage("candidatesTokenCount").num.toInt,
            totalTokens = usage("totalTokenCount").num.toInt
          )
        }.toOption

        val message = AssistantMessage(
          contentOpt = if (textContent.nonEmpty) Some(textContent) else None,
          toolCalls = toolCalls
        )

        Right(
          Completion(
            id = UUID.randomUUID().toString,
            content = textContent,
            model = config.model,
            toolCalls = toolCalls.toList,
            created = System.currentTimeMillis() / 1000,
            message = message,
            usage = usageOpt
          )
        )
      }
    }.toEither.left.map(e => e.toLLMError).flatten

  /**
   * Parse a streaming chunk from Gemini.
   */
  private def parseStreamChunk(json: ujson.Value, messageId: String): Option[StreamedChunk] =
    Try {
      val candidates = json("candidates").arr
      if (candidates.nonEmpty) {
        val candidate = candidates.head
        val content   = candidate("content")
        val parts     = content("parts").arr

        // Extract text delta
        val textContent = parts
          .filter(p => p.obj.contains("text"))
          .map(_("text").str)
          .mkString

        // Extract finish reason
        val finishReason = Try(candidate("finishReason").str).toOption

        // Extract tool calls
        val toolCallOpt = parts
          .filter(p => p.obj.contains("functionCall"))
          .headOption
          .map { p =>
            val fc = p("functionCall")
            ToolCall(
              id = UUID.randomUUID().toString,
              name = fc("name").str,
              arguments = fc("args")
            )
          }

        Some(
          StreamedChunk(
            id = messageId,
            content = if (textContent.nonEmpty) Some(textContent) else None,
            toolCall = toolCallOpt,
            finishReason = finishReason
          )
        )
      } else {
        None
      }
    }.toOption.flatten

  /**
   * Handle error responses from Gemini API.
   */
  private def handleErrorResponse(statusCode: Int, body: String): Result[Nothing] = {
    logger.error(s"[Gemini] Error response: $statusCode - $body")

    val errorMessage = Try {
      val json = ujson.read(body)
      json("error")("message").str
    }.getOrElse(body)

    statusCode match {
      case 401 | 403 => Left(AuthenticationError("gemini", errorMessage))
      case 429       => Left(RateLimitError("gemini"))
      case 400       => Left(ValidationError("request", errorMessage))
      case _         => Left(ValidationError("api", s"Gemini API error ($statusCode): $errorMessage"))
    }
  }
}

object GeminiClient {
  import org.llm4s.types.TryOps

  def apply(config: GeminiConfig): Result[GeminiClient] =
    Try(new GeminiClient(config)).toResult
}
