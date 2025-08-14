package org.llm4s.szork

import org.llm4s.agent.{Agent, AgentState, AgentStatus}
import org.llm4s.llmconnect.LLM
import org.llm4s.llmconnect.model.{LLMError, UserMessage, AssistantMessage}
import org.llm4s.toolapi.ToolRegistry
import org.slf4j.LoggerFactory

class GameEngine(sessionId: String = "", theme: Option[String] = None, artStyle: Option[String] = None, adventureOutline: Option[AdventureOutline] = None) {
  private val logger = LoggerFactory.getLogger("GameEngine")
  
  private val themeDescription = theme.getOrElse("classic fantasy dungeon adventure")
  private val artStyleDescription = artStyle match {
    case Some("pixel") => "pixel art style, 16-bit retro video game aesthetic, blocky pixels, limited color palette, nostalgic 8-bit/16-bit graphics"
    case Some("illustration") => "professional pencil drawing style, detailed graphite art, realistic shading, fine pencil strokes, sketch-like illustration"
    case Some("painting") => "oil painting style, fully rendered painting with realistic lighting and textures, painterly brushstrokes, fine art aesthetic"
    case Some("comic") => "comic book art style with bold lines, cel-shaded coloring, graphic novel aesthetic, dynamic comic book illustration"
    case _ => "fantasy art style, detailed digital illustration"
  }
  
  private val adventureOutlinePrompt = adventureOutline match {
    case Some(outline) => AdventureGenerator.outlineToSystemPrompt(outline)
    case None => ""
  }
  
  private val gamePrompt =
    s"""You are a Dungeon Master guiding a text adventure game in the classic Infocom tradition.
      |
      |Adventure Theme: $themeDescription
      |Art Style: $artStyleDescription
      |
      |$adventureOutlinePrompt
      |
      |TEXT ADVENTURE WRITING CONVENTIONS:
      |
      |ROOM DESCRIPTIONS:
      |- Follow the verbose/brief convention: First visit shows full description (2-4 sentences), subsequent visits can be briefer
      |- Use progressive disclosure: Initial description shows immediate impressions and essential elements
      |- Structure: General atmosphere → permanent fixtures → portable objects → NPCs → exits
      |- Use environmental storytelling: "One section of the bookshelf shows less dust" rather than "there might be a secret door"
      |- Layer information for different player types: Essential info first, optional details reward examination
      |
      |OBJECT PRESENTATION:
      |- Use Infocom house style: "There is a brass lantern here" or "A battery-powered lantern is on the trophy case"
      |- Include state information naturally: "(closed)", "(providing light)", "(locked)"
      |- Avoid special capitalization - trust players to explore mentioned items
      |- Follow noun prominence: Important objects appear explicitly, not buried in prose
      |- Three-tier importance: Essential objects mentioned 3 times, useful twice, atmospheric once
      |
      |NARRATIVE STYLE:
      |- Second-person present tense: "You are in a forest clearing"
      |- Balance atmosphere with functional clarity - every sentence advances atmosphere or gameplay, ideally both
      |- Selective detail: Not every noun needs examination, but interactive elements receive subtle emphasis
      |- Information density: Edit ruthlessly - every word must earn its place
      |- Fair play principle: All puzzle information discoverable within game world logic
      |
      |EXIT PRESENTATION:
      |- Integrate naturally into prose: "A path leads north into the forest" rather than "Exits: north"
      |- Distinguish between open and blocked paths: "an open door leads north" vs "a closed door blocks the northern exit"
      |- Use standard directions: cardinal (north/south/east/west), vertical (up/down), relative (in/out)
      |
      |HINTING TECHNIQUES:
      |- Rule of three: First exposure introduces, second establishes pattern, third reveals significance
      |- Position important objects prominently with distinctive adjectives
      |- Environmental inconsistencies guide discovery: dust patterns, temperature variations, sounds
      |- Examination reveals deeper layers - reward thorough investigation
      |
      |STATE CHANGES & DYNAMICS:
      |- Reflect player actions through dynamic descriptions
      |- Clear state transparency: "The lever clicks into place"
      |- Persistent consequences: A smashed vase permanently alters room descriptions
      |- Conditional text based on player knowledge: "strange markings" become "ancient Elvish runes" after finding translation
      |
      |INVENTORY MANAGEMENT:
      |You have access to three inventory management tools that you MUST use:
      |- list_inventory: Use this to check what items the player currently has
      |- add_inventory_item: Use this when the player picks up or receives an item
      |- remove_inventory_item: Use this when the player uses, drops, or gives away an item
      |
      |IMPORTANT INVENTORY RULES:
      |- When a player picks up an item, ALWAYS use add_inventory_item tool
      |- When a player uses/drops an item, ALWAYS use remove_inventory_item tool
      |- Check inventory with list_inventory before using items
      |- Track items consistently - if an item is picked up in one location, it should be in inventory
      |- Items in the "items" field of a location are available to pick up, not already owned
      |
      |Response Format:
      |
      |IMPORTANT: Choose the appropriate response type based on the action:
      |
      |TYPE 1 - FULL SCENE (for movement, look, or scene changes):
      |{
      |  "responseType": "fullScene",
      |  "locationId": "unique_location_id",  // e.g., "dungeon_entrance", "forest_path_1"
      |  "locationName": "Human Readable Name",  // e.g., "Dungeon Entrance", "Forest Path"
      |  "narrationText": "Evocative room description following text adventure conventions. First visit: 2-4 sentences with atmosphere and interactive elements. Include object descriptions like 'There is a brass lantern here' for items.",
      |  "imageDescription": "Detailed 2-3 sentence visual description for image generation in $artStyleDescription. Include colors, lighting, atmosphere, architectural details, and visual elements appropriate for the art style.",
      |  "musicDescription": "Detailed atmospheric description for music generation. Include mood, tempo, instruments, and emotional tone.",
      |  "musicMood": "One of: entrance, exploration, combat, victory, dungeon, forest, town, mystery, castle, underwater, temple, boss, stealth, treasure, danger, peaceful",
      |  "exits": [
      |    {"direction": "north", "locationId": "forest_clearing", "description": "A winding path disappears into the dark forest"},
      |    {"direction": "south", "locationId": "village_square", "description": "The cobblestone road leads back to the village"}
      |  ],
      |  "items": ["brass_lantern", "mysterious_key"],  // Items available in this location to pick up
      |  "npcs": ["old_wizard", "guard"]  // NPCs present in this location
      |}
      |
      |TYPE 2 - SIMPLE RESPONSE (for examine, help, inventory, interactions without scene change):
      |{
      |  "responseType": "simple",
      |  "narrationText": "The response text without room description. For examine: detailed object description. For help: command list. For inventory: 'You are carrying: ...' etc.",
      |  "locationId": "current_location_id",  // Keep the same location ID as before
      |  "actionTaken": "examine/help/inventory/talk/use/etc"  // What action was performed
      |}
      |
      |Rules:
      |- Follow classic text adventure writing conventions throughout
      |- Use "fullScene" response ONLY for: movement to new location, "look" command, or major scene changes
      |- Use "simple" response for: examine, help, inventory, talk, use item (without movement), take/drop items
      |- NarrationText should be 2-4 sentences for first visits, can be briefer for return visits
      |- Balance evocative prose with clear gameplay information
      |- ImageDescription should be rich and detailed (50-100 words) focusing on visual elements in the $artStyleDescription
      |- IMPORTANT: Always describe scenes specifically for the art style: $artStyleDescription
      |- MusicDescription should evoke the atmosphere and mood (30-50 words)
      |- Always provide consistent locationIds for navigation
      |- Track player location, inventory, and game state
      |- Enforce movement restrictions based on exits
      |- Use consistent locationIds when revisiting locations
      |- Use inventory tools for ALL item management
      |
      |Special commands and their response types:
      |- "help" - SIMPLE response: List basic commands
      |- "hint" - SIMPLE response: Provide contextual hint
      |- "inventory" or "i" - SIMPLE response: Use list_inventory tool, respond with "You are carrying: ..."
      |- "look" or "l" - FULL SCENE response: Complete room description
      |- "examine [object]" or "x [object]" - SIMPLE response: Detailed object examination
      |- "take [item]" or "get [item]" - SIMPLE response: Use add_inventory_item tool, confirm action
      |- "drop [item]" - SIMPLE response: Use remove_inventory_item tool, confirm action
      |- "use [item]" - SIMPLE response unless it causes movement
      |- "talk to [npc]" - SIMPLE response: NPC dialogue
      |- Movement commands - FULL SCENE response: Complete new location description
      |""".stripMargin

  private val client = LLM.client()
  private val toolRegistry = new ToolRegistry(GameTools.allTools)
  private val agent = new Agent(client)
  
  private var currentState: AgentState = _
  private var currentScene: Option[GameScene] = None
  private var visitedLocations: Set[String] = Set.empty
  private var conversationHistory: List[ConversationEntry] = List.empty
  private val createdAt: Long = System.currentTimeMillis()
  private var sessionStartTime: Long = System.currentTimeMillis()
  private var totalPlayTime: Long = 0L  // Accumulated play time from previous sessions
  private var adventureTitle: Option[String] = adventureOutline.map(_.title)
  
  def initialize(): String = {
    logger.info(s"[$sessionId] Initializing game with theme: $themeDescription")
    
    // Clear inventory for new game
    GameTools.clearInventory()
    
    val initPrompt = s"Generate the opening scene for the adventure. The player is just beginning their journey. Create a fullScene JSON response with the starting location, following the text adventure conventions specified in your instructions."
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
        val (responseText, sceneOpt) = parseResponseData(response) match {
          case Some(scene: GameScene) =>
            // Full scene response - update current scene
            currentScene = Some(scene)
            visitedLocations += scene.locationId
            logger.info(s"[$sessionId] Full scene response: ${scene.locationId} - ${scene.locationName}")
            (scene.narrationText, Some(scene))
            
          case Some(simple: SimpleResponse) =>
            // Simple response - keep current scene, just return the text
            logger.info(s"[$sessionId] Simple response for action: ${simple.actionTaken}")
            (simple.narrationText, currentScene) // Keep the current scene
            
          case None =>
            // Fallback to raw text if parsing fails
            logger.warn(s"[$sessionId] Could not parse structured response, using raw text")
            (if (response.nonEmpty) response else "No response", currentScene)
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
  
  private def parseResponseData(response: String): Option[GameResponseData] = {
    if (response.isEmpty) return None
    
    try {
      // Try to extract JSON from the response (it might be wrapped in other text)
      val jsonStart = response.indexOf('{')
      val jsonEnd = response.lastIndexOf('}')
      
      if (jsonStart >= 0 && jsonEnd > jsonStart) {
        val jsonStr = response.substring(jsonStart, jsonEnd + 1)
        GameResponseData.fromJson(jsonStr) match {
          case Right(data) => Some(data)
          case Left(error) =>
            logger.warn(s"[$sessionId] Failed to parse response JSON: $error")
            None
        }
      } else {
        None
      }
    } catch {
      case e: Exception =>
        logger.error(s"[$sessionId] Error parsing response", e)
        None
    }
  }
  
  private def parseSceneFromResponse(response: String): Option[GameScene] = {
    parseResponseData(response) match {
      case Some(scene: GameScene) => Some(scene)
      case _ => None
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
    
    // Include art style prominently in the image prompt
    val styledPrompt = artStyle match {
      case Some("pixel") => s"Pixel art style image: $imagePrompt. Create in 16-bit pixel art style with blocky pixels and limited color palette."
      case Some("illustration") => s"Pencil sketch illustration: $imagePrompt. Create as a detailed pencil drawing with graphite shading."
      case Some("painting") => s"Oil painting: $imagePrompt. Create as a traditional oil painting with visible brushstrokes."
      case Some("comic") => s"Comic book art: $imagePrompt. Create in comic book style with bold outlines and cel shading."
      case _ => s"$imagePrompt, rendered in $artStyleDescription"
    }
    logger.info(s"[$sessionId] Generating scene image with prompt: ${styledPrompt.take(100)}...")
    val imageGen = ImageGeneration()
    
    // Use cached version if available - pass art style for cache matching
    val artStyleForCache = artStyle.getOrElse("fantasy")
    imageGen.generateSceneWithCache(styledPrompt, artStyleForCache, gameId, locationId) match {
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
    val currentSessionTime = System.currentTimeMillis() - sessionStartTime
    GameState(
      gameId = gameId,
      theme = gameTheme,
      artStyle = gameArtStyle,
      currentScene = currentScene,
      visitedLocations = visitedLocations,
      conversationHistory = conversationHistory,
      inventory = GameTools.getInventory,
      createdAt = createdAt,
      lastSaved = System.currentTimeMillis(),
      lastPlayed = System.currentTimeMillis(),
      totalPlayTime = totalPlayTime + currentSessionTime,
      adventureTitle = adventureTitle
    )
  }
  
  // Restore game from saved state
  def restoreGameState(state: GameState): Unit = {
    currentScene = state.currentScene
    visitedLocations = state.visitedLocations
    conversationHistory = state.conversationHistory
    GameTools.setInventory(state.inventory)
    totalPlayTime = state.totalPlayTime
    sessionStartTime = System.currentTimeMillis()  // Reset session timer when restoring
    adventureTitle = state.adventureTitle
    
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
  
  def getAdventureTitle: Option[String] = adventureTitle
  
  def setAdventureTitle(title: String): Unit = {
    adventureTitle = Some(title)
  }
}

object GameEngine {
  def create(sessionId: String = "", theme: Option[String] = None, artStyle: Option[String] = None, adventureOutline: Option[AdventureOutline] = None): GameEngine = 
    new GameEngine(sessionId, theme, artStyle, adventureOutline)
}