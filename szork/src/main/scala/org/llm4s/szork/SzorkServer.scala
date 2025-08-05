package org.llm4s.szork

import cask._
import scala.collection.mutable
import org.slf4j.LoggerFactory
import java.util.Base64

case class GameSession(
  id: String,
  engine: GameEngine
)

case class CommandRequest(command: String)
case class CommandResponse(response: String, sessionId: String)

object SzorkServer extends cask.Main with cask.Routes {
  
  private val logger = LoggerFactory.getLogger(getClass)
  private val sessions = mutable.Map[String, GameSession]()


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
    
    val engine = GameEngine.create()
    val initialMessage = engine.initialize()
    
    val session = GameSession(
      id = sessionId,
      engine = engine
    )
    
    sessions(sessionId) = session
    logger.info(s"Game session started: $sessionId")
    
    ujson.Obj(
      "sessionId" -> sessionId,
      "message" -> initialMessage,
      "status" -> "started"
    )
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
            
            val result = ujson.Obj(
              "sessionId" -> sessionId,
              "response" -> gameResponse.text,
              "status" -> "success"
            )
            
            // Add audio if generated
            gameResponse.audioBase64.foreach { audio =>
              result("audio") = audio
            }
            
            // Add image if generated
            gameResponse.imageBase64.foreach { image =>
              result("image") = image
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
                  
                  val result = ujson.Obj(
                    "sessionId" -> sessionId,
                    "transcription" -> transcribedText,
                    "response" -> gameResponse.text,
                    "status" -> "success"
                  )
                  
                  // Add audio if generated
                  gameResponse.audioBase64.foreach { audio =>
                    result("audio") = audio
                  }
                  
                  // Add image if generated
                  gameResponse.imageBase64.foreach { image =>
                    result("image") = image
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