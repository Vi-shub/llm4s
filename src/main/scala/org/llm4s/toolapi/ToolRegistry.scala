package org.llm4s.toolapi

/**
 * Request model for tool calls
 */
case class ToolCallRequest(
  functionName: String,
  arguments: ujson.Value
)

/**
 * Error types for tool calls
 */
sealed trait ToolCallError
object ToolCallError {
  case class UnknownFunction(name: String)          extends ToolCallError
  case class InvalidArguments(errors: List[String]) extends ToolCallError
  case class ExecutionError(cause: Throwable)       extends ToolCallError
}

/**
 * Registry for tool functions with execution capabilities
 */
class ToolRegistry(initialTools: Seq[ToolFunction[_, _]] = Seq.empty) {

  private val toolsMap = scala.collection.mutable.Map[String, ToolFunction[_, _]]()

  // Initialize with provided tools
  initialTools.foreach(tool => toolsMap.put(tool.name, tool))

  def tools: Seq[ToolFunction[_, _]] = toolsMap.values.toSeq

  // Add a new tool to the registry
  def addTool(tool: ToolFunction[_, _]): Unit =
    toolsMap.put(tool.name, tool)

  // Add multiple tools to the registry
  def addTools(newTools: Seq[ToolFunction[_, _]]): Unit =
    newTools.foreach(addTool)

  // Remove a tool from the registry by name
  def removeTool(name: String): Option[ToolFunction[_, _]] =
    toolsMap.remove(name)

  // Clear all tools from the registry
  def clearTools(): Unit =
    toolsMap.clear()

  // Check if a tool exists in the registry
  def hasTool(name: String): Boolean = toolsMap.contains(name)

  // Get a specific tool by name
  def getTool(name: String): Option[ToolFunction[_, _]] = toolsMap.get(name)

  // Execute a tool call
  def execute(request: ToolCallRequest): Either[ToolCallError, ujson.Value] =
    toolsMap.get(request.functionName) match {
      case Some(tool) =>
        try
          tool.execute(request.arguments)
        catch {
          case e: Exception => Left(ToolCallError.ExecutionError(e))
        }

      case None => Left(ToolCallError.UnknownFunction(request.functionName))
    }

  // Generate OpenAI tool definitions for all tools
  def getOpenAITools(strict: Boolean = true): ujson.Arr =
    ujson.Arr.from(tools.map(_.toOpenAITool(strict)))

  // Generate a specific format of tool definitions for a particular LLM provider
  def getToolDefinitions(provider: String): ujson.Value = provider.toLowerCase match {
    case "openai"    => getOpenAITools()
    case "anthropic" => getOpenAITools() // Currently using the same format
    case "gemini"    => getOpenAITools() // May need adjustment for Google's format
    case _           => throw new IllegalArgumentException(s"Unsupported LLM provider: $provider")
  }

  /**
   * Adds the tools from this registry to an Azure OpenAI ChatCompletionsOptions
   *
   * @param chatOptions The chat options to add the tools to
   * @return The updated chat options
   */
  def addToAzureOptions(
    chatOptions: com.azure.ai.openai.models.ChatCompletionsOptions
  ): com.azure.ai.openai.models.ChatCompletionsOptions =
    AzureToolHelper.addToolsToOptions(this, chatOptions)
}
