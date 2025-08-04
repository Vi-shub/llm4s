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
    "Welcome th the adventure - type look"
  }
  
  case class GameResponse(text: String, audioBase64: Option[String] = None, imageBase64: Option[String] = None)
  
  def processCommand(command: String, generateAudio: Boolean = true, generateImage: Boolean = false): Either[LLMError, GameResponse] = {
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
        
        // Generate image if requested and it's a new scene
        val imageBase64 = if (generateImage && isNewScene(responseText)) {
          logger.info(s"Generating image for new scene")
          val imageGen = ImageGeneration()
          val scenePrompt = extractSceneDescription(responseText)
          imageGen.generateScene(scenePrompt, ImageGeneration.STYLE_FANTASY) match {
            case Right(image) =>
              logger.info(s"Generated scene image, base64 length: ${image.length}")
              Some(image)
            case Left(error) =>
              logger.error(s"Failed to generate image: $error")
              None
          }
        } else {
          None
        }
        
        Right(GameResponse(responseText, audioBase64, imageBase64))
        
      case Left(error) =>
        logger.error(s"Error processing command: $error")
        Left(error)
    }
  }
  
  def getMessageCount: Int = currentState.conversation.messages.length
  
  def getState: AgentState = currentState
  
  private def isNewScene(response: String): Boolean = {
    // Detect if this is a new scene based on keywords
    val sceneIndicators = List(
      "you enter", "you arrive", "you find yourself",
      "you see", "before you", "you are in",
      "you stand", "exits:", "you reach"
    )
    val lowerResponse = response.toLowerCase
    sceneIndicators.exists(lowerResponse.contains)
  }
  
  private def extractSceneDescription(response: String): String = {
    // Extract the main scene description, focusing on visual elements
    val sentences = response.split("[.!?]").filter(_.trim.nonEmpty)
    val visualSentences = sentences.filter { s =>
      val lower = s.toLowerCase
      lower.contains("see") || lower.contains("before") || 
      lower.contains("stand") || lower.contains("enter") ||
      lower.contains("room") || lower.contains("cave") ||
      lower.contains("forest") || lower.contains("dungeon") ||
      lower.contains("hall") || lower.contains("chamber")
    }
    
    val description = if (visualSentences.nonEmpty) {
      visualSentences.mkString(". ")
    } else {
      sentences.headOption.getOrElse(response.take(100))
    }
    
    // Clean up and enhance for image generation
    description.replaceAll("You ", "A fantasy adventurer ")
      .replaceAll("you ", "the adventurer ")
  }
}

object GameEngine {
  def create(): GameEngine = new GameEngine()
}