package org.llm4s.szork

import org.slf4j.LoggerFactory
import requests._
import org.llm4s.config.EnvLoader
import ujson._

class ImageGeneration {
  private val logger = LoggerFactory.getLogger("ImageGen")
  private val apiKey = EnvLoader.get("OPENAI_API_KEY").getOrElse(
    throw new IllegalStateException("OPENAI_API_KEY not found in environment")
  )
  
  def generateScene(prompt: String, style: String = "fantasy art, digital painting"): Either[String, String] = {
    logger.info(s"Generating image for prompt: ${prompt.take(100)}...")
    
    val fullPrompt = s"$prompt, $style, highly detailed, atmospheric lighting"
    
    try {
      val response = post(
        "https://api.openai.com/v1/images/generations",
        headers = Map(
          "Authorization" -> s"Bearer $apiKey",
          "Content-Type" -> "application/json"
        ),
        data = Obj(
          "model" -> "dall-e-2",
          "prompt" -> fullPrompt,
          "n" -> 1,
          "size" -> "256x256",
          "response_format" -> "b64_json"
        ).toString,
        readTimeout = 30000,  // 30 seconds for DALL-E to generate image
        connectTimeout = 10000  // 10 seconds to establish connection
      )
      
      if (response.statusCode == 200) {
        val json = read(response.text())
        val base64Image = json("data")(0)("b64_json").str
        logger.info(s"Image generation successful, base64 length: ${base64Image.length}")
        Right(base64Image)
      } else {
        val error = s"Image generation failed with status ${response.statusCode}: ${response.text()}"
        logger.error(error)
        Left(error)
      }
    } catch {
      case e: Exception =>
        logger.error("Error during image generation", e)
        Left(s"Image generation error: ${e.getMessage}")
    }
  }
  
}

object ImageGeneration {
  def apply(): ImageGeneration = new ImageGeneration()
  
  // Style presets for consistent art direction
  val STYLE_FANTASY = "fantasy art, digital painting, concept art"
  val STYLE_DUNGEON = "dark fantasy, dungeon, atmospheric, ominous"
  val STYLE_FOREST = "enchanted forest, mystical, natural lighting"
  val STYLE_TOWN = "medieval town, bustling, warm colors"
  val STYLE_COMBAT = "dynamic action scene, dramatic lighting"
}