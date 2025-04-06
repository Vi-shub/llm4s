package org.llm4s.tokens

import com.knuddels.jtokkit.Encodings
import org.llm4s.identity.TokenizerId

class TokenCounter {
  private val registry = Encodings.newDefaultEncodingRegistry

  def countTokens(text: String, tokenizerId: TokenizerId): Int = {
    val encoderOptional = registry.getEncoding(tokenizerId.name)
    if (encoderOptional.isPresent) {
      val encoder = encoderOptional.get()
      encoder.encode(text).size()
    } else {
      throw new RuntimeException(s"Tokenizer not found: ${tokenizerId.name}")
    }
  }
}
