package org.llm4s.szork

import org.llm4s.agent.{Agent, AgentState, AgentStatus}
import org.llm4s.llmconnect.LLM
import org.llm4s.llmconnect.model.{LLMError, UserMessage, AssistantMessage}
import org.llm4s.toolapi.ToolRegistry
import org.slf4j.LoggerFactory

class GameEngine(sessionId: String = "", theme: Option[String] = None, artStyle: Option[String] = None) {
  private val logger = LoggerFactory.getLogger("GameEngine")
  
  private val themeDescription = theme.getOrElse("classic fantasy dungeon adventure")
  private val artStyleDescription = artStyle match {
    case Some("pixel") => "pixel art style, 16-bit retro game aesthetic"
    case Some("illustration") => "professional pencil drawing, detailed graphite art, realistic shading, fine pencil strokes"
    case Some("painting") => "fully rendered painting style with realistic lighting and textures"
    case Some("comic") => "comic book style with bold lines and cel-shaded coloring"
    case _ => "fantasy art style"
  }
  
  private val gamePrompt =
    s"""You are a Dungeon Master guiding a text adventure game.
      |
      |Adventure Theme: $themeDescription
      |Art Style: $artStyleDescription
      |
      |IMPORTANT: You must respond with a JSON object containing structured scene information.
      |
      |Response Format:
      |{
      |  "locationId": "unique_location_id",  // e.g., "dungeon_entrance", "forest_path_1"
      |  "locationName": "Human Readable Name",  // e.g., "Dungeon Entrance", "Forest Path"
      |  "narrationText": "Brief 2-3 sentence description for the player",
      |  "imageDescription": "Detailed 2-3 sentence visual description for image generation. Include colors, lighting, atmosphere, architectural details, and visual elements.",
      |  "musicDescription": "Detailed atmospheric description for music generation. Include mood, tempo, instruments, and emotional tone.",
      |  "musicMood": "One of: entrance, exploration, combat, victory, dungeon, forest, town, mystery, castle, underwater, temple, boss, stealth, treasure, danger, peaceful",
      |  "exits": [
      |    {"direction": "north", "locationId": "forest_clearing", "description": "A path leads into the forest"},
      |    {"direction": "south", "locationId": "village_square", "description": "The village lies behind you"}
      |  ],
      |  "items": ["torch", "old_key"],  // Optional: items in this location
      |  "npcs": ["old_wizard", "guard"]  // Optional: NPCs in this location
      |}
      |
      |Rules:
      |- Keep narrationText under 50 words
      |- ImageDescription should be rich and detailed (50-100 words) focusing on visual elements
      |- MusicDescription should evoke the atmosphere and mood (30-50 words)
      |- Always provide consistent locationIds for navigation
      |- Track player location, inventory, and game state
      |- Enforce movement restrictions based on exits
      |- Use consistent locationIds when revisiting locations
      |
      |Special commands:
      |- "help" - List basic commands but still return JSON
      |- "hint" - Give hint in narrationText but still return JSON
      |- "inventory" - List items in narrationText but still return JSON
      |- "look" - Redescribe current location with full JSON
      |""".stripMargin

  private val client = LLM.client()
  private val toolRegistry = new ToolRegistry(Nil)
  private val agent = new Agent(client)
  
  private var currentState: AgentState = _
  private var currentScene: Option[GameScene] = None
  private var visitedLocations: Set[String] = Set.empty
  private var conversationHistory: List[ConversationEntry] = List.empty
  private val createdAt: Long = System.currentTimeMillis()
  
  def initialize(): String = {
    logger.info(s"[$sessionId] Initializing game with theme: $themeDescription")
    val initPrompt = s"Let's begin the adventure! Create an opening scene for this adventure theme: $themeDescription. Start by describing the initial scene where the player begins their journey. Return a complete JSON response."
    currentState = agent.initialize(
      initPrompt,
      toolRegistry,
      systemPromptAddition = Some(gamePrompt)
    )
    
    // Automatically run the initial scene generation
    agent.run(currentState) match {
      case Right(newState) =>
        currentState = newState
        // Extract the initial scene description from the agent's response
        val assistantMessages = newState.conversation.messages.collect { case msg: AssistantMessage => msg }
        val responseContent = assistantMessages.headOption.map(_.content).getOrElse("")
        
        // Try to parse as JSON scene
        parseSceneFromResponse(responseContent) match {
          case Some(scene) =>
            currentScene = Some(scene)
            visitedLocations += scene.locationId
            logger.info(s"[$sessionId] Game initialized with scene: ${scene.locationId} - ${scene.locationName}")
            // Track initial conversation
            trackConversation("assistant", scene.narrationText)
            scene.narrationText
          case None =>
            logger.warn(s"[$sessionId] Failed to parse structured response, using raw text")
            responseContent.take(200) // Fallback to raw text if parsing fails
        }
        
      case Left(error) =>
        logger.error(s"[$sessionId] Failed to initialize game: $error")
        "Welcome to the adventure! You stand at the entrance of a dark dungeon. Stone steps lead down into darkness. Exits: north (into dungeon)."
    }
  }
  
  case class GameResponse(
    text: String, 
    audioBase64: Option[String] = None, 
    imageBase64: Option[String] = None,
    backgroundMusicBase64: Option[String] = None,
    musicMood: Option[String] = None,
    scene: Option[GameScene] = None
  )
  
  def processCommand(command: String, generateAudio: Boolean = true): Either[LLMError, GameResponse] = {
    logger.debug(s"[$sessionId] Processing command: $command")
    
    // Track user command in conversation history
    trackConversation("user", command)
    
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
        
        // Try to parse the response as structured JSON
        val (responseText, sceneOpt) = parseSceneFromResponse(response) match {
          case Some(scene) =>
            currentScene = Some(scene)
            visitedLocations += scene.locationId
            logger.info(s"[$sessionId] Parsed scene: ${scene.locationId} - ${scene.locationName}")
            (scene.narrationText, Some(scene))
          case None =>
            logger.warn(s"[$sessionId] Could not parse structured response, using raw text")
            (if (response.nonEmpty) response else "No response", None)
        }
        
        // Generate audio if requested
        val audioBase64 = if (generateAudio && responseText.nonEmpty) {
          val audioStartTime = System.currentTimeMillis()
          logger.info(s"[$sessionId] Generating audio (${responseText.length} chars)")
          val tts = TextToSpeech()
          tts.synthesizeToBase64(responseText, TextToSpeech.VOICE_NOVA) match {
            case Right(audio) => 
              val audioTime = System.currentTimeMillis() - audioStartTime
              logger.info(s"[$sessionId] Audio generated in ${audioTime}ms, base64: ${audio.length}")
              Some(audio)
            case Left(error) => 
              logger.error(s"[$sessionId] Failed to generate audio: $error")
              None
          }
        } else {
          logger.info(s"[$sessionId] Skipping audio (generateAudio=$generateAudio, empty=${responseText.isEmpty})")
          None
        }
        
        // Track assistant response in conversation history
        trackConversation("assistant", responseText)
        
        // Image generation is now handled asynchronously in the server
        // Background music generation is also handled asynchronously
        
        Right(GameResponse(responseText, audioBase64, None, None, None, sceneOpt))
        
      case Left(error) =>
        logger.error(s"[$sessionId] Error processing command: $error")
        Left(error)
    }
  }
  
  def getMessageCount: Int = currentState.conversation.messages.length
  
  def getState: AgentState = currentState
  
  private def parseSceneFromResponse(response: String): Option[GameScene] = {
    if (response.isEmpty) return None
    
    try {
      // Try to extract JSON from the response (it might be wrapped in other text)
      val jsonStart = response.indexOf('{')
      val jsonEnd = response.lastIndexOf('}')
      
      if (jsonStart >= 0 && jsonEnd > jsonStart) {
        val jsonStr = response.substring(jsonStart, jsonEnd + 1)
        GameScene.fromJson(jsonStr) match {
          case Right(scene) => Some(scene)
          case Left(error) =>
            logger.warn(s"[$sessionId] Failed to parse scene JSON: $error")
            None
        }
      } else {
        None
      }
    } catch {
      case e: Exception =>
        logger.error(s"[$sessionId] Error parsing scene response", e)
        None
    }
  }
  
  def shouldGenerateSceneImage(responseText: String): Boolean = {
    // Check if we have a current scene or if it's a new scene based on text
    currentScene.isDefined || isNewScene(responseText)
  }
  
  def generateSceneImage(responseText: String, gameId: Option[String] = None): Option[String] = {
    // Use detailed description from current scene if available
    val (imagePrompt, locationId) = currentScene match {
      case Some(scene) =>
        logger.info(s"[$sessionId] Using structured image description for ${scene.locationId}")
        (scene.imageDescription, Some(scene.locationId))
      case None if isNewScene(responseText) =>
        logger.info(s"[$sessionId] No structured scene, extracting from text")
        (extractSceneDescription(responseText), None)
      case _ =>
        return None
    }
    
    // Include art style in the image prompt
    val styledPrompt = s"$imagePrompt, rendered in $artStyleDescription"
    logger.info(s"[$sessionId] Generating scene image with prompt: ${styledPrompt.take(100)}...")
    val imageGen = ImageGeneration()
    
    // Use cached version if available
    imageGen.generateSceneWithCache(styledPrompt, "", gameId, locationId) match {
      case Right(image) =>
        logger.info(s"[$sessionId] Scene image generated/retrieved, base64: ${image.length}")
        Some(image)
      case Left(error) =>
        logger.error(s"[$sessionId] Failed to generate image: $error")
        None
    }
  }
  
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
  
  def shouldGenerateBackgroundMusic(responseText: String): Boolean = {
    // Generate music if we have a scene or detect scene change
    currentScene.isDefined || {
      val lowerText = responseText.toLowerCase
      isNewScene(responseText) || 
      lowerText.contains("battle") || 
      lowerText.contains("victory") ||
      lowerText.contains("defeated") ||
      lowerText.contains("enter") ||
      lowerText.contains("arrive")
    }
  }
  
  def generateBackgroundMusic(responseText: String, gameId: Option[String] = None): Option[(String, String)] = {
    if (shouldGenerateBackgroundMusic(responseText)) {
      logger.info(s"[$sessionId] Checking if background music should be generated")
      try {
        val musicGen = MusicGeneration()
        
        // Check if music generation is available
        if (!musicGen.isAvailable) {
          logger.info(s"[$sessionId] Music generation disabled - no API key configured")
          return None
        }
        
        // Use structured mood and description if available
        val (mood, contextText, locationId) = currentScene match {
          case Some(scene) =>
            // Map the scene's mood string to a MusicMood object
            val moodObj = getMusicMoodFromString(musicGen, scene.musicMood)
            logger.info(s"[$sessionId] Using structured music for ${scene.locationId}: mood=${scene.musicMood}")
            (moodObj, scene.musicDescription, Some(scene.locationId))
          case None =>
            val detectedMood = musicGen.detectMoodFromText(responseText)
            logger.info(s"[$sessionId] Detected mood: ${detectedMood.name} from text")
            (detectedMood, responseText, None)
        }
        
        logger.info(s"[$sessionId] Generating background music with mood: ${mood.name}")
        musicGen.generateMusicWithCache(mood, contextText, gameId, locationId) match {
          case Right(musicBase64) =>
            logger.info(s"[$sessionId] Background music generated/retrieved for mood: ${mood.name}, base64: ${musicBase64.length}")
            Some((musicBase64, mood.name))
          case Left(error) =>
            logger.warn(s"[$sessionId] Music generation not available: $error")
            None
        }
      } catch {
        case e: Exception =>
          logger.warn(s"[$sessionId] Music generation disabled due to error: ${e.getMessage}")
          None
      }
    } else {
      None
    }
  }
  
  private def getMusicMoodFromString(musicGen: MusicGeneration, moodStr: String): musicGen.MusicMood = {
    import musicGen.MusicMoods._
    moodStr.toLowerCase match {
      case "entrance" => ENTRANCE
      case "exploration" => EXPLORATION
      case "combat" => COMBAT
      case "victory" => VICTORY
      case "dungeon" => DUNGEON
      case "forest" => FOREST
      case "town" => TOWN
      case "mystery" => MYSTERY
      case "castle" => CASTLE
      case "underwater" => UNDERWATER
      case "temple" => TEMPLE
      case "boss" => BOSS
      case "stealth" => STEALTH
      case "treasure" => TREASURE
      case "danger" => DANGER
      case "peaceful" => PEACEFUL
      case _ => EXPLORATION // Default fallback
    }
  }
  
  def getCurrentScene: Option[GameScene] = currentScene
  
  // State extraction for persistence
  def getGameState(gameId: String, gameTheme: Option[GameTheme], gameArtStyle: Option[ArtStyle]): GameState = {
    GameState(
      gameId = gameId,
      theme = gameTheme,
      artStyle = gameArtStyle,
      currentScene = currentScene,
      visitedLocations = visitedLocations,
      conversationHistory = conversationHistory,
      createdAt = createdAt,
      lastSaved = System.currentTimeMillis()
    )
  }
  
  // Restore game from saved state
  def restoreGameState(state: GameState): Unit = {
    currentScene = state.currentScene
    visitedLocations = state.visitedLocations
    conversationHistory = state.conversationHistory
    
    // Reconstruct conversation for the agent
    // We'll create a simplified conversation with just the essential messages
    val messages = state.conversationHistory.flatMap { entry =>
      entry.role match {
        case "user" => Some(UserMessage(content = entry.content))
        case "assistant" => Some(AssistantMessage(content = entry.content))
        case _ => None
      }
    }
    
    // Initialize the agent with the restored conversation
    if (messages.nonEmpty) {
      currentState = agent.initialize(
        messages.head.content,
        toolRegistry,
        systemPromptAddition = Some(gamePrompt)
      )
      
      // Add the rest of the messages to restore conversation context
      messages.tail.foreach { msg =>
        currentState = currentState.addMessage(msg)
      }
    } else {
      // If no conversation history, initialize normally
      initialize()
    }
    
    logger.info(s"[$sessionId] Game state restored with ${conversationHistory.size} conversation entries")
  }
  
  // Add conversation tracking to processCommand
  private def trackConversation(role: String, content: String): Unit = {
    conversationHistory = conversationHistory :+ ConversationEntry(
      role = role,
      content = content,
      timestamp = System.currentTimeMillis()
    )
  }
}

object GameEngine {
  def create(sessionId: String = "", theme: Option[String] = None, artStyle: Option[String] = None): GameEngine = 
    new GameEngine(sessionId, theme, artStyle)
}