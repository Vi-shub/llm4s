package org.llm4s.szork

import org.slf4j.LoggerFactory
import requests._
import org.llm4s.config.EnvLoader
import java.util.Base64
import ujson._
import java.io.ByteArrayOutputStream
import java.net.URI

class MusicGeneration {
  private val logger = LoggerFactory.getLogger(getClass)
  private val replicateApiKey = EnvLoader.get("REPLICATE_API_KEY").getOrElse(
    throw new IllegalStateException("REPLICATE_API_KEY not found in environment")
  )
  
  case class MusicMood(
    name: String,
    prompt: String,
    duration: Int = 10  // seconds
  )
  
  object MusicMoods {
    val ENTRANCE = MusicMood("entrance", "mysterious cave entrance, ambient, dark, foreboding, orchestral fantasy")
    val EXPLORATION = MusicMood("exploration", "adventurous, curious, light orchestral, fantasy exploration")
    val COMBAT = MusicMood("combat", "intense battle music, fast drums, dramatic orchestral, action")
    val VICTORY = MusicMood("victory", "triumphant fanfare, heroic, celebratory orchestral")
    val DUNGEON = MusicMood("dungeon", "dark dungeon ambience, ominous, suspenseful, low strings")
    val FOREST = MusicMood("forest", "peaceful forest, nature sounds, soft flutes, mystical")
    val TOWN = MusicMood("town", "medieval town, bustling marketplace, folk instruments, cheerful")
    val MYSTERY = MusicMood("mystery", "mysterious, puzzle solving, subtle tension, contemplative")
  }
  
  def generateMusic(mood: MusicMood): Either[String, String] = {
    logger.info(s"Generating music for mood: ${mood.name}")
    
    try {
      // Create prediction
      val createResponse = post(
        "https://api.replicate.com/v1/predictions",
        headers = Map(
          "Authorization" -> s"Bearer $replicateApiKey",
          "Content-Type" -> "application/json"
        ),
        data = Obj(
          "version" -> "671ac645ce5e552cc63a54a2bbff63fcf798043055d2dac5fc9e36a837eedcfb",
          "input" -> Obj(
            "prompt" -> mood.prompt,
            "duration" -> mood.duration,
            "temperature" -> 1.0,
            "top_k" -> 250,
            "top_p" -> 0.0,
            "classifier_free_guidance" -> 3
          )
        ).toString
      )
      
      if (createResponse.statusCode != 201) {
        val error = s"Failed to create prediction: ${createResponse.statusCode} - ${createResponse.text()}"
        logger.error(error)
        return Left(error)
      }
      
      val predictionId = read(createResponse.text())("id").str
      logger.info(s"Created prediction: $predictionId")
      
      // Poll for completion
      val result = pollPrediction(predictionId)
      result match {
        case Right(audioUrl) =>
          downloadAndEncodeAudio(audioUrl)
        case Left(error) =>
          Left(error)
      }
      
    } catch {
      case e: Exception =>
        logger.error("Error during music generation", e)
        Left(s"Music generation error: ${e.getMessage}")
    }
  }
  
  private def pollPrediction(predictionId: String, maxAttempts: Int = 30): Either[String, String] = {
    var attempts = 0
    
    while (attempts < maxAttempts) {
      val response = get(
        s"https://api.replicate.com/v1/predictions/$predictionId",
        headers = Map("Authorization" -> s"Bearer $replicateApiKey")
      )
      
      if (response.statusCode == 200) {
        val json = read(response.text())
        val status = json("status").str
        
        logger.debug(s"Prediction status: $status")
        
        status match {
          case "succeeded" =>
            val audioUrl = json("output").str
            logger.info(s"Music generation succeeded: $audioUrl")
            return Right(audioUrl)
            
          case "failed" =>
            val error = json.obj.get("error").map(_.str).getOrElse("Unknown error")
            logger.error(s"Music generation failed: $error")
            return Left(s"Generation failed: $error")
            
          case "processing" | "starting" =>
            Thread.sleep(1000)  // Wait 1 second before polling again
            attempts += 1
            
          case _ =>
            return Left(s"Unknown status: $status")
        }
      } else {
        return Left(s"Failed to get prediction status: ${response.statusCode}")
      }
    }
    
    Left("Timeout waiting for music generation")
  }
  
  private def downloadAndEncodeAudio(audioUrl: String): Either[String, String] = {
    try {
      logger.info(s"Downloading audio from: $audioUrl")
      
      val uri = new URI(audioUrl)
      val url = uri.toURL()
      val connection = url.openConnection()
      val inputStream = connection.getInputStream
      
      val outputStream = new ByteArrayOutputStream()
      val buffer = new Array[Byte](4096)
      var bytesRead = 0
      
      while ({bytesRead = inputStream.read(buffer); bytesRead != -1}) {
        outputStream.write(buffer, 0, bytesRead)
      }
      
      inputStream.close()
      val audioBytes = outputStream.toByteArray
      outputStream.close()
      
      val base64Audio = Base64.getEncoder.encodeToString(audioBytes)
      logger.info(s"Downloaded and encoded audio, size: ${audioBytes.length} bytes")
      
      Right(base64Audio)
      
    } catch {
      case e: Exception =>
        logger.error("Error downloading audio", e)
        Left(s"Failed to download audio: ${e.getMessage}")
    }
  }
  
  def detectMoodFromText(text: String): MusicMood = {
    val lowerText = text.toLowerCase
    
    if (lowerText.contains("battle") || lowerText.contains("attack") || lowerText.contains("fight")) {
      MusicMoods.COMBAT
    } else if (lowerText.contains("victory") || lowerText.contains("defeated") || lowerText.contains("won")) {
      MusicMoods.VICTORY
    } else if (lowerText.contains("dungeon") || lowerText.contains("dark") || lowerText.contains("cave")) {
      MusicMoods.DUNGEON
    } else if (lowerText.contains("forest") || lowerText.contains("trees") || lowerText.contains("nature")) {
      MusicMoods.FOREST
    } else if (lowerText.contains("town") || lowerText.contains("village") || lowerText.contains("market")) {
      MusicMoods.TOWN
    } else if (lowerText.contains("puzzle") || lowerText.contains("mystery") || lowerText.contains("riddle")) {
      MusicMoods.MYSTERY
    } else if (lowerText.contains("entrance") || lowerText.contains("begin")) {
      MusicMoods.ENTRANCE
    } else {
      MusicMoods.EXPLORATION
    }
  }
}

object MusicGeneration {
  def apply(): MusicGeneration = new MusicGeneration()
}