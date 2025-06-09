package org.llm4s.agent

import requests.*
import upickle.default.*
import java.time.Instant
import java.time.format.DateTimeFormatter
import scala.util.{Try, Failure, Success}
import org.llm4s.llmconnect.model.{AssistantMessage, ToolMessage, ToolCall, Message}

object LangfuseTraceExporter {
  private val langfuseUrl = sys.env.getOrElse("LANGFUSE_URL", "https://cloud.langfuse.com/api/public/ingestion")
  private val publicKey = sys.env.getOrElse("LANGFUSE_PUBLIC_KEY", "")
  private val secretKey = sys.env.getOrElse("LANGFUSE_SECRET_KEY", "")

  // Helper to generate RFC3339/ISO8601 timestamp
  private def nowIso: String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

  // Helper to generate a unique event id (UUID v4)
  private def uuid: String = java.util.UUID.randomUUID().toString

  /**
    * Export the full agent trace to Langfuse using the batched ingestion API.
    */
  def exportAgentTrace(state: AgentState): Unit = {
    if (publicKey.isEmpty || secretKey.isEmpty) {
      println("[Langfuse] Public or secret key not set in environment. Skipping export.")
      return
    }

    val traceId = uuid
    val now = nowIso
    val batchEvents = scala.collection.mutable.ArrayBuffer[ujson.Obj]()

    // Create trace-create event
    val traceInput = if (state.userQuery.nonEmpty) state.userQuery else "No user query"
    val traceOutput = state.conversation.messages.lastOption.map(_.content).filter(_.nonEmpty).getOrElse("No output")
    
    val traceEvent = ujson.Obj(
      "id" -> uuid,
      "timestamp" -> now,
      "type" -> "trace-create",
      "body" -> ujson.Obj(
        "id" -> traceId,
        "timestamp" -> now,
        "name" -> "LLM4S Agent Run",
        "input" -> traceInput,
        "output" -> traceOutput,
        "userId" -> "llm4s-user",
        "sessionId" -> s"session-${System.currentTimeMillis()}",
        "metadata" -> ujson.Obj(
          "framework" -> "llm4s",
          "messageCount" -> state.conversation.messages.length
        ),
        "tags" -> ujson.Arr("llm4s", "agent")
      )
    )
    batchEvents += traceEvent

    // Create observation events for each message
    state.conversation.messages.zipWithIndex.foreach { case (msg, idx) =>
      msg match {
        case am: AssistantMessage if am.toolCalls.nonEmpty =>
          // Create generation event for assistant message with tool calls
          val generationEvent = ujson.Obj(
            "id" -> uuid,
            "timestamp" -> now,
            "type" -> "generation-create",
            "body" -> ujson.Obj(
              "id" -> s"${traceId}-gen-$idx",
              "traceId" -> traceId,
              "name" -> s"Assistant Response $idx",
              "startTime" -> now,
              "endTime" -> now,
              "input" -> ujson.Obj("content" -> (if (am.content.nonEmpty) am.content else "No content")),
              "output" -> ujson.Obj("toolCalls" -> am.toolCalls.length),
              "model" -> "assistant",
              "modelParameters" -> ujson.Obj(),
              "metadata" -> ujson.Obj(
                "role" -> am.role,
                "toolCallCount" -> am.toolCalls.length
              )
            )
          )
          batchEvents += generationEvent

        case tm: ToolMessage =>
          // Create span event for tool execution
          val spanEvent = ujson.Obj(
            "id" -> uuid,
            "timestamp" -> now,
            "type" -> "span-create",
            "body" -> ujson.Obj(
              "id" -> s"${traceId}-span-$idx",
              "traceId" -> traceId,
              "name" -> s"Tool Execution: ${tm.toolCallId}",
              "startTime" -> now,
              "endTime" -> now,
              "input" -> ujson.Obj("toolCallId" -> tm.toolCallId),
              "output" -> ujson.Obj("content" -> (if (tm.content.nonEmpty) tm.content.take(500) else "No content")),
              "metadata" -> ujson.Obj(
                "role" -> tm.role,
                "toolCallId" -> tm.toolCallId
              )
            )
          )
          batchEvents += spanEvent

        case userMsg: org.llm4s.llmconnect.model.UserMessage =>
          // Create event for user message
          val eventEvent = ujson.Obj(
            "id" -> uuid,
            "timestamp" -> now,
            "type" -> "event-create",
            "body" -> ujson.Obj(
              "id" -> s"${traceId}-event-$idx",
              "traceId" -> traceId,
              "name" -> s"User Input $idx",
              "startTime" -> now,
              "input" -> ujson.Obj("content" -> userMsg.content),
              "metadata" -> ujson.Obj(
                "role" -> userMsg.role
              )
            )
          )
          batchEvents += eventEvent

        case sysMsg: org.llm4s.llmconnect.model.SystemMessage =>
          // Create event for system message
          val eventEvent = ujson.Obj(
            "id" -> uuid,
            "timestamp" -> now,
            "type" -> "event-create",
            "body" -> ujson.Obj(
              "id" -> s"${traceId}-event-$idx",
              "traceId" -> traceId,
              "name" -> s"System Message $idx",
              "startTime" -> now,
              "input" -> ujson.Obj("content" -> sysMsg.content),
              "metadata" -> ujson.Obj(
                "role" -> sysMsg.role
              )
            )
          )
          batchEvents += eventEvent

        case _ =>
          // Handle other message types
          val eventEvent = ujson.Obj(
            "id" -> uuid,
            "timestamp" -> now,
            "type" -> "event-create",
            "body" -> ujson.Obj(
              "id" -> s"${traceId}-event-$idx",
              "traceId" -> traceId,
              "name" -> s"Message $idx: ${msg.role}",
              "startTime" -> now,
              "input" -> ujson.Obj("content" -> msg.content),
              "metadata" -> ujson.Obj(
                "role" -> msg.role
              )
            )
          )
          batchEvents += eventEvent
      }
    }

    // Create the batch payload
    val batchPayload = ujson.Obj(
      "batch" -> ujson.Arr(batchEvents.toSeq*)
    )

    // Send the batch to Langfuse
    Try {
      val response = requests.post(
        langfuseUrl,
        data = batchPayload.render(),
        headers = Map("Content-Type" -> "application/json"),
        auth = (publicKey, secretKey),
        readTimeout = 30000
      )
      
      if (response.statusCode == 207) {
        // 207 is expected for batch operations, parse the response to check for errors
        println(s"[Langfuse] Batch export response: ${response.statusCode}")
        println(s"[Langfuse] Response body: ${response.text()}")
      } else if (response.statusCode >= 200 && response.statusCode < 300) {
        println(s"[Langfuse] Batch export successful: ${response.statusCode}")
        println(s"[Langfuse] Exported ${batchEvents.length} events to trace $traceId")
      } else {
        println(s"[Langfuse] Batch export failed: ${response.statusCode} ${response.text()}")
      }
    } match {
      case Failure(e) =>
        println(s"[Langfuse] Batch export failed with exception: ${e.getMessage}")
        e.printStackTrace()
      case Success(_) => 
    }
  }

  /**
    * Sample usage: Export a fake agent trace for demonstration
    */
  def exportSampleTrace(): Unit = {
    // Create a fake AgentState with a user query, assistant reply, and tool call
    val toolCall = ToolCall("tool-1", "search", ujson.Obj("query" -> "Scala Langfuse integration"))
    val assistantMsg = AssistantMessage("Let me search for that...", Seq(toolCall))
    val toolMsg = ToolMessage("tool-1", "{\"result\":\"Here is what I found...\"}")
    val userMsg = org.llm4s.llmconnect.model.UserMessage("How do I integrate Scala with Langfuse?")
    val sysMsg = org.llm4s.llmconnect.model.SystemMessage("You are a helpful assistant.")
    val conversation = org.llm4s.llmconnect.model.Conversation(Seq(sysMsg, userMsg, assistantMsg, toolMsg))
    val fakeState = AgentState(
      conversation = conversation,
      tools = new org.llm4s.toolapi.ToolRegistry(Seq()),
      userQuery = userMsg.content,
      status = AgentStatus.Complete,
      logs = Seq("[assistant] tools: 1 tool calls requested (search)", "[tool] search (100ms): {\"result\":\"Here is what I found...\"}")
    )
    exportAgentTrace(fakeState)
  }
}