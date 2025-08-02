package org.llm4s.szork

import cask._
import org.llm4s.agent.{Agent, AgentState}
import org.llm4s.llmconnect.LLM
import org.llm4s.llmconnect.model.{UserMessage, Conversation}
import org.llm4s.toolapi.ToolRegistry
import scala.collection.mutable

case class GameSession(
  id: String,
  agent: Agent,
  var state: AgentState,
  var conversation: Conversation
)

case class CommandRequest(command: String)
case class CommandResponse(response: String, sessionId: String)

object SzorkServer extends MainRoutes {
  
  private val sessions = mutable.Map[String, GameSession]()
  
  private val gamePrompt =
    """You are a Dungeon Master guiding a fantasy text adventure game.
      |Describe the current scene then as the player takes actions track their progress
      |and manage their actions - e.g. if they say 'go north' and there is no north keep them
      |in the current location and provide an appropriate message.
      |Keep track of the player's state, location, and inventory in memory.
      |At each step, provide a description of the current scene and any relevant information.
      |If the player asks for help, provide a brief overview of the game mechanics.
      |If the player asks for a hint, provide a hint that is relevant to the current
      |scene or situation.
      |If the player asks for a summary of their current state, provide a summary
      |of their current location, inventory, and any relevant information.
      |If the player asks for a list of available actions, provide a list of actions
      |that are available in the current scene.
      |""".stripMargin

  @get("/")
  def index() = {
    "Szork Game Server - Use the frontend or API endpoints"
  }

  @get("/api/health")
  def health() = {
    ujson.Obj(
      "status" -> "healthy",
      "service" -> "szork-server"
    )
  }

  @post("/api/game/start")
  def startGame() = {
    val sessionId = java.util.UUID.randomUUID().toString
    val client = LLM.client()
    val toolRegistry = new ToolRegistry(Nil)
    val agent = new Agent(client)
    
    val initialState = agent.initialize(
      "Let's begin the adventure!",
      toolRegistry,
      systemPromptAddition = Some(gamePrompt)
    )
    
    val session = GameSession(
      id = sessionId,
      agent = agent,
      state = initialState,
      conversation = initialState.conversation
    )
    
    sessions(sessionId) = session
    
    ujson.Obj(
      "sessionId" -> sessionId,
      "message" -> "You are at the entrance to a dark cave.",
      "status" -> "started"
    )
  }

  @post("/api/game/command")
  def processCommand(request: Request) = {
    val json = ujson.read(request.text())
    val sessionId = json("sessionId").str
    val command = json("command").str
    
    sessions.get(sessionId) match {
      case Some(session) =>
        // Add user message to conversation
        session.state = session.state.addMessage(UserMessage(content = command))
        
        // Run the agent
        session.agent.run(session.state) match {
          case Right(newState) =>
            session.state = newState
            session.conversation = newState.conversation
            
            val response = newState.conversation.messages.lastOption
              .map(_.content)
              .getOrElse("No response")
            
            ujson.Obj(
              "sessionId" -> sessionId,
              "response" -> response,
              "status" -> "success"
            )
            
          case Left(error) =>
            ujson.Obj(
              "sessionId" -> sessionId,
              "error" -> error.toString,
              "status" -> "error"
            )
        }
        
      case None =>
        ujson.Obj(
          "error" -> "Session not found",
          "status" -> "error"
        )
    }
  }

  @get("/api/game/session/:sessionId")
  def getSession(sessionId: String) = {
    sessions.get(sessionId) match {
      case Some(session) =>
        ujson.Obj(
          "sessionId" -> sessionId,
          "messageCount" -> session.conversation.messages.length,
          "status" -> "active"
        )
      case None =>
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

  println("Starting Szork Server on http://localhost:8080")
  println("API endpoints:")
  println("  POST /api/game/start - Start a new game session")
  println("  POST /api/game/command - Send a command to the game")
  println("  GET  /api/game/session/:id - Get session info")
  
  // Initialize the server - the parent's main() will handle starting it
  initialize()
}