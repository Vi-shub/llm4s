package org.llm4s.szork

import ujson._

case class Exit(
  direction: String,  // "north", "south", "east", "west", "up", "down", etc.
  locationId: String, // Target location ID
  description: Option[String] = None // Optional description of what's in that direction
)

case class GameScene(
  locationId: String,           // Unique identifier for this location (e.g., "forest_entrance", "dark_cave_1")
  locationName: String,          // Human-readable location name
  narrationText: String,         // The text to display/narrate to the player
  imageDescription: String,      // Detailed description for image generation
  musicDescription: String,      // Detailed description for music generation
  musicMood: String,            // Mood keyword for music (e.g., "exploration", "combat", "mystery")
  exits: List[Exit],            // Available exits from this location
  items: List[String] = Nil,    // Items present in this location
  npcs: List[String] = Nil      // NPCs present in this location
)

object GameScene {
  def fromJson(json: String): Either[String, GameScene] = {
    try {
      val parsed = read(json)
      
      val exits = parsed("exits").arr.map { exitJson =>
        Exit(
          direction = exitJson("direction").str,
          locationId = exitJson("locationId").str,
          description = exitJson.obj.get("description").map(_.str)
        )
      }.toList
      
      val scene = GameScene(
        locationId = parsed("locationId").str,
        locationName = parsed("locationName").str,
        narrationText = parsed("narrationText").str,
        imageDescription = parsed("imageDescription").str,
        musicDescription = parsed("musicDescription").str,
        musicMood = parsed("musicMood").str,
        exits = exits,
        items = parsed.obj.get("items").map(_.arr.map(_.str).toList).getOrElse(Nil),
        npcs = parsed.obj.get("npcs").map(_.arr.map(_.str).toList).getOrElse(Nil)
      )
      
      Right(scene)
    } catch {
      case e: Exception => 
        Left(s"Failed to parse GameScene JSON: ${e.getMessage}")
    }
  }
  
  def toJson(scene: GameScene): String = {
    Obj(
      "locationId" -> scene.locationId,
      "locationName" -> scene.locationName,
      "narrationText" -> scene.narrationText,
      "imageDescription" -> scene.imageDescription,
      "musicDescription" -> scene.musicDescription,
      "musicMood" -> scene.musicMood,
      "exits" -> scene.exits.map { exit =>
        val exitObj = Obj(
          "direction" -> exit.direction,
          "locationId" -> exit.locationId
        )
        exit.description.foreach(desc => exitObj("description") = desc)
        exitObj
      },
      "items" -> scene.items,
      "npcs" -> scene.npcs
    ).toString
  }
}