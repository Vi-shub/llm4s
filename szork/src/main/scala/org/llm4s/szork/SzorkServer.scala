package org.llm4s.szork

import cask._
import scala.collection.mutable
import org.slf4j.LoggerFactory
import java.util.Base64
import scala.concurrent.{Future, ExecutionContext}
import java.util.concurrent.Executors

case class GameSession(
  id: String,
  engine: GameEngine,
  pendingImages: mutable.Map[Int, Option[String]] = mutable.Map.empty
)

case class CommandRequest(command: String)
case class CommandResponse(response: String, sessionId: String)

object SzorkServer extends cask.Main with cask.Routes {
  
  private val logger = LoggerFactory.getLogger("SzorkServer")
  private val sessions = mutable.Map[String, GameSession]()
  private val imageExecutor = Executors.newFixedThreadPool(4)
  private implicit val imageEC: ExecutionContext = ExecutionContext.fromExecutor(imageExecutor)


  @get("/api/health")
  def health() = {
    logger.debug("Health check requested")
    ujson.Obj(
      "status" -> "healthy",
      "service" -> "szork-server"
    )
  }

  @post("/api/game/start")
  def startGame() = {
    logger.info("Starting new game session")
    val sessionId = java.util.UUID.randomUUID().toString
    
    val engine = GameEngine.create(sessionId)
    val initialMessage = engine.initialize()
    
    val session = GameSession(
      id = sessionId,
      engine = engine
    )
    
    sessions(sessionId) = session
    logger.info(s"[$sessionId] Game session started")
    
    val messageIndex = engine.getMessageCount - 1
    val result = ujson.Obj(
      "sessionId" -> sessionId,
      "message" -> initialMessage,
      "status" -> "started",
      "messageIndex" -> messageIndex
    )
    
    // Check if the initial scene should have an image
    if (engine.generateSceneImage(initialMessage).isEmpty) {
      result("hasImage") = false
    } else {
      result("hasImage") = true
      // Generate image asynchronously for initial scene
      Future {
        logger.info(s"[$sessionId] Starting async image generation for initial scene, message $messageIndex")
        val imageOpt = engine.generateSceneImage(initialMessage)
        session.pendingImages(messageIndex) = imageOpt
        logger.info(s"[$sessionId] Async image generation completed for initial scene, message $messageIndex")
      }(imageEC)
    }
    
    result
  }

  @post("/api/game/command")
  def processCommand(request: Request) = {
    val json = ujson.read(request.text())
    val sessionId = json("sessionId").str
    val command = json("command").str
    
    logger.info(s"Processing command for session $sessionId: $command")
    
    sessions.get(sessionId) match {
      case Some(session) =>
        logger.debug(s"Running game engine for session $sessionId")
        val startTime = System.currentTimeMillis()
        
        session.engine.processCommand(command) match {
          case Right(gameResponse) =>
            val processingTime = System.currentTimeMillis() - startTime
            logger.info(s"Command processed successfully for session $sessionId in ${processingTime}ms")
            
            val messageIndex = session.engine.getMessageCount - 1
            val result = ujson.Obj(
              "sessionId" -> sessionId,
              "response" -> gameResponse.text,
              "status" -> "success",
              "messageIndex" -> messageIndex
            )
            
            // Add audio if generated
            gameResponse.audioBase64.foreach { audio =>
              result("audio") = audio
            }
            
            // Check if this is a scene that needs an image
            if (session.engine.generateSceneImage(gameResponse.text).isEmpty) {
              result("hasImage") = false
            } else {
              result("hasImage") = true
              // Generate image asynchronously
              Future {
                logger.info(s"Starting async image generation for session $sessionId, message $messageIndex")
                val imageOpt = session.engine.generateSceneImage(gameResponse.text)
                session.pendingImages(messageIndex) = imageOpt
                logger.info(s"Async image generation completed for session $sessionId, message $messageIndex")
              }(imageEC)
            }
            
            result
            
          case Left(error) =>
            logger.error(s"Error processing command for session $sessionId: $error")
            ujson.Obj(
              "sessionId" -> sessionId,
              "error" -> error.toString,
              "status" -> "error"
            )
        }
        
      case None =>
        logger.warn(s"Session not found: $sessionId")
        ujson.Obj(
          "error" -> "Session not found",
          "status" -> "error"
        )
    }
  }

  @post("/api/game/audio")
  def processAudioCommand(request: Request) = {
    logger.info("Processing audio command")
    
    try {
      // For simplicity, we'll use JSON with base64 encoded audio
      val json = ujson.read(request.text())
      val sessionId = json("sessionId").str
      val audioBase64 = json("audio").str
      
      logger.info(s"Processing audio for session: $sessionId")
      
      // Decode base64 audio
      val audioBytes = Base64.getDecoder.decode(audioBase64)
      
      // Transcribe audio to text
      val speechToText = SpeechToText()
      speechToText.transcribeBytes(audioBytes) match {
        case Right(transcribedText) =>
          logger.info(s"Transcribed text: $transcribedText")
          
          // Process the transcribed text as a regular command
          sessions.get(sessionId) match {
            case Some(session) =>
              val startTime = System.currentTimeMillis()
              
              session.engine.processCommand(transcribedText) match {
                case Right(gameResponse) =>
                  val processingTime = System.currentTimeMillis() - startTime
                  logger.info(s"Audio command processed successfully for session $sessionId in ${processingTime}ms")
                  
                  val messageIndex = session.engine.getMessageCount - 1
                  val result = ujson.Obj(
                    "sessionId" -> sessionId,
                    "transcription" -> transcribedText,
                    "response" -> gameResponse.text,
                    "status" -> "success",
                    "messageIndex" -> messageIndex
                  )
                  
                  // Add audio if generated
                  gameResponse.audioBase64.foreach { audio =>
                    result("audio") = audio
                  }
                  
                  // Check if this is a scene that needs an image
                  if (session.engine.generateSceneImage(gameResponse.text).isEmpty) {
                    result("hasImage") = false
                  } else {
                    result("hasImage") = true
                    // Generate image asynchronously
                    Future {
                      logger.info(s"Starting async image generation for session $sessionId, message $messageIndex (audio)")
                      val imageOpt = session.engine.generateSceneImage(gameResponse.text)
                      session.pendingImages(messageIndex) = imageOpt
                      logger.info(s"Async image generation completed for session $sessionId, message $messageIndex (audio)")
                    }(imageEC)
                  }
                  
                  result
                  
                case Left(error) =>
                  logger.error(s"Error processing transcribed command for session $sessionId: $error")
                  ujson.Obj(
                    "sessionId" -> sessionId,
                    "error" -> error.toString,
                    "status" -> "error"
                  )
              }
              
            case None =>
              logger.warn(s"Session not found: $sessionId")
              ujson.Obj(
                "error" -> "Session not found",
                "status" -> "error"
              )
          }
          
        case Left(error) =>
          logger.error(s"Transcription failed: $error")
          ujson.Obj(
            "error" -> s"Speech recognition failed: $error",
            "status" -> "error"
          )
      }
    } catch {
      case e: Exception =>
        logger.error("Error processing audio", e)
        ujson.Obj(
          "error" -> s"Error processing audio: ${e.getMessage}",
          "status" -> "error"
        )
    }
  }

  @get("/api/game/image/:sessionId/:messageIndex")
  def getImage(sessionId: String, messageIndex: String) = {
    logger.debug(s"Checking for image: session=$sessionId, message=$messageIndex")
    
    try {
      val index = messageIndex.toInt
      sessions.get(sessionId) match {
        case Some(session) =>
          session.pendingImages.get(index) match {
            case Some(Some(imageBase64)) =>
              logger.info(s"Image found for session $sessionId, message $index")
              ujson.Obj(
                "status" -> "ready",
                "image" -> imageBase64
              )
            case Some(None) =>
              logger.info(s"Image generation failed for session $sessionId, message $index")
              ujson.Obj(
                "status" -> "failed"
              )
            case None =>
              logger.debug(s"Image still generating for session $sessionId, message $index")
              ujson.Obj(
                "status" -> "pending"
              )
          }
        case None =>
          logger.warn(s"Session not found: $sessionId")
          ujson.Obj(
            "status" -> "error",
            "error" -> "Session not found"
          )
      }
    } catch {
      case _: NumberFormatException =>
        ujson.Obj(
          "status" -> "error",
          "error" -> "Invalid message index"
        )
    }
  }
  
  @get("/api/game/session/:sessionId")
  def getSession(sessionId: String) = {
    logger.debug(s"Getting session info for: $sessionId")
    sessions.get(sessionId) match {
      case Some(session) =>
        ujson.Obj(
          "sessionId" -> sessionId,
          "messageCount" -> session.engine.getMessageCount,
          "status" -> "active"
        )
      case None =>
        logger.warn(s"Session not found: $sessionId")
        ujson.Obj(
          "error" -> "Session not found",
          "status" -> "error"
        )
    }
  }

  @get("/") 
  def serveApp() = {
    """<!DOCTYPE html>
      |<html>
      |<head><title>Szork</title></head>
      |<body>
      |<h1>Szork Server</h1>
      |<p>Use the frontend at http://localhost:3000 or the API endpoints directly.</p>
      |</body>
      |</html>""".stripMargin
  }

  logger.info("Starting Szork Server on http://localhost:8080")
  logger.info("API endpoints:")
  logger.info("  POST /api/game/start - Start a new game session")
  logger.info("  POST /api/game/command - Send a command to the game")
  logger.info("  GET  /api/game/session/:id - Get session info")
  
  println("Starting Szork Server on http://localhost:8080")
  println("API endpoints:")
  println("  POST /api/game/start - Start a new game session")
  println("  POST /api/game/command - Send a command to the game")
  println("  GET  /api/game/session/:id - Get session info")

  override def verbose: Boolean = true
  override def port: Int = 8080
  override def host: String = "0.0.0.0"
  
  def allRoutes = Seq(this)
  
  // Initialize routes
  initialize()
}