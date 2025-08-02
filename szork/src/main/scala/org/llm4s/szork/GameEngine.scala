package org.llm4s.szork

import org.llm4s.agent.{Agent, AgentState, AgentStatus}
import org.llm4s.llmconnect.LLM
import org.llm4s.llmconnect.model.{LLMError, UserMessage, AssistantMessage}
import org.llm4s.toolapi.ToolRegistry
import org.slf4j.LoggerFactory

class GameEngine {
  private val logger = LoggerFactory.getLogger(getClass)
  
  private val gamePrompt =
    """You are a Dungeon Master guiding a fantasy text adventure game.
      |
      |IMPORTANT: Keep descriptions brief and concise - 2-3 sentences maximum for scene descriptions.
      |Focus on essential details only: what the player sees, available exits, and notable objects.
      |
      |Rules:
      |- Track player location, inventory, and game state
      |- Enforce movement restrictions (e.g., can't go north if there's no exit north)
      |- Describe scenes briefly when player enters new areas
      |- List available directions clearly (e.g., "Exits: north, east")
      |- Keep responses under 50 words unless specifically asked for more detail
      |
      |Special commands:
      |- "help" - List basic commands (go, take, use, inventory, look)
      |- "hint" - Give a brief contextual hint
      |- "inventory" - List carried items
      |- "look" - Redescribe current location
      |""".stripMargin

  private val client = LLM.client()
  private val toolRegistry = new ToolRegistry(Nil)
  private val agent = new Agent(client)
  
  private var currentState: AgentState = _
  
  def initialize(): String = {
    logger.info("Initializing new game engine")
    currentState = agent.initialize(
      "Let's begin the adventure!",
      toolRegistry,
      systemPromptAddition = Some(gamePrompt)
    )
    "You are at the entrance to a dark cave."
  }
  
  case class GameResponse(text: String, audioBase64: Option[String] = None)
  
  def processCommand(command: String, generateAudio: Boolean = true): Either[LLMError, GameResponse] = {
    logger.debug(s"Processing command: $command")
    
    // Track message count before adding user message
    val previousMessageCount = currentState.conversation.messages.length
    
    // Add user message to conversation
    currentState = currentState
      .addMessage(UserMessage(content = command))
      .withStatus(AgentStatus.InProgress)
    
    // Run the agent
    agent.run(currentState) match {
      case Right(newState) =>
        // Get only the new messages added by the agent
        val newMessages = newState.conversation.messages.drop(previousMessageCount + 1) // +1 to skip the user message we just added
        val assistantMessages = newMessages.collect { case msg: AssistantMessage => msg }
        val response = assistantMessages.map(_.content).mkString("\n\n")
        
        logger.debug(s"Agent added ${newMessages.length} messages, ${assistantMessages.length} are assistant messages")
        
        currentState = newState
        val responseText = if (response.nonEmpty) response else "No response"
        
        // Generate audio if requested
        val audioBase64 = if (generateAudio && responseText.nonEmpty) {
          logger.info(s"Generating audio for response (generateAudio=$generateAudio, text length=${responseText.length})")
          val tts = TextToSpeech()
          tts.synthesizeToBase64(responseText, TextToSpeech.VOICE_NOVA) match {
            case Right(audio) => 
              logger.info(s"Generated audio narration for response, base64 length: ${audio.length}")
              Some(audio)
            case Left(error) => 
              logger.error(s"Failed to generate audio: $error")
              None
          }
        } else {
          logger.info(s"Skipping audio generation (generateAudio=$generateAudio, text empty=${responseText.isEmpty})")
          None
        }
        
        Right(GameResponse(responseText, audioBase64))
        
      case Left(error) =>
        logger.error(s"Error processing command: $error")
        Left(error)
    }
  }
  
  def getMessageCount: Int = currentState.conversation.messages.length
  
  def getState: AgentState = currentState
}

object GameEngine {
  def create(): GameEngine = new GameEngine()
}