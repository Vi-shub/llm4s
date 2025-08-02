package org.llm4s.szork

import cask._

object SzorkSimpleServer extends MainRoutes {
  
  // Simple in-memory game state for testing without LLM
  case class SimpleGameState(
    sessionId: String,
    location: String = "entrance",
    inventory: List[String] = List.empty,
    messages: List[String] = List("You are at the entrance to a dark cave.")
  )
  
  private val sessions = scala.collection.mutable.Map[String, SimpleGameState]()
  
  @get("/")
  def index() = {

    """<!doctype html>
      |<html>
      |<head><title>Szork</title></head>
      |<body>
      |<h1>Szork Simple Server</h1>
      |<p>Use the frontend at http://localhost:3000 or the API endpoints directly.</p>
      |</body>
      |</html>""".stripMargin
  }

  @get("/api/health")
  def health() = {
    ujson.Obj(
      "status" -> "healthy",
      "service" -> "szork-simple-server"
    )
  }

  @post("/api/game/start")
  def startGame() = {
    val sessionId = java.util.UUID.randomUUID().toString
    val state = SimpleGameState(sessionId)
    sessions(sessionId) = state
    
    ujson.Obj(
      "sessionId" -> sessionId,
      "message" -> state.messages.last,
      "status" -> "started"
    )
  }

  @post("/api/game/command")
  def processCommand(request: Request) = {
    val json = ujson.read(request.text())
    val sessionId = json("sessionId").str
    val command = json("command").str.toLowerCase.trim
    
    sessions.get(sessionId) match {
      case Some(state) =>
        val (newState, response) = processSimpleCommand(state, command)
        sessions(sessionId) = newState
        
        ujson.Obj(
          "sessionId" -> sessionId,
          "response" -> response,
          "status" -> "success"
        )
        
      case None =>
        ujson.Obj(
          "error" -> "Session not found. Please start a new game.",
          "status" -> "error"
        )
    }
  }
  
  private def processSimpleCommand(state: SimpleGameState, command: String): (SimpleGameState, String) = {
    command match {
      case "help" =>
        (state, "Available commands: look, go [direction], inventory, take [item], quit")
        
      case "look" =>
        val description = state.location match {
          case "entrance" => "You are at the entrance to a dark cave. The mouth of the cave yawns before you to the north."
          case "cave" => "You are inside a dark cave. You can see the entrance to the south. There's a shiny object on the ground."
          case _ => "You look around."
        }
        (state, description)
        
      case "inventory" | "i" =>
        val items = if (state.inventory.isEmpty) "Your inventory is empty." 
                   else s"You are carrying: ${state.inventory.mkString(", ")}"
        (state, items)
        
      case cmd if cmd.startsWith("go ") =>
        val direction = cmd.drop(3)
        (state.location, direction) match {
          case ("entrance", "north") => 
            (state.copy(location = "cave"), "You enter the dark cave.")
          case ("cave", "south") =>
            (state.copy(location = "entrance"), "You exit the cave.")
          case _ =>
            (state, "You can't go that way.")
        }
        
      case cmd if cmd.startsWith("take ") =>
        val item = cmd.drop(5)
        if (state.location == "cave" && item == "object") {
          (state.copy(inventory = "shiny object" :: state.inventory), 
           "You take the shiny object.")
        } else {
          (state, "There's nothing here by that name.")
        }
        
      case "quit" =>
        (state, "Thanks for playing! (Note: The session is still active)")
        
      case _ =>
        (state, s"I don't understand '$command'. Type 'help' for available commands.")
    }
  }

  println("Starting Szork Simple Server on http://localhost:8080")
  println("This is a simple version without LLM integration for testing")
  println("API endpoints:")
  println("  POST /api/game/start - Start a new game session")
  println("  POST /api/game/command - Send a command to the game")

  // Initialize the server - the parent's main() will handle starting it
  initialize()
}