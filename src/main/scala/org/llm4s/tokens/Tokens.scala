package org.llm4s.tokens

import com.knuddels.jtokkit.Encodings
import org.llm4s.identity.TokenizerId
import org.llm4s.tokens.TokenCounter

case class Token(tokenId: Int) {
  override def toString: String = s"$tokenId"
}

trait StringTokenizer {
  def encode(text: String): List[Token]
}

object Tokenizer {
  private val registry = Encodings.newDefaultEncodingRegistry

  def lookupStringTokenizer(tokenizerId: TokenizerId): Option[StringTokenizer] = {
    val encoderOptional = registry.getEncoding(tokenizerId.name)
    if (encoderOptional.isPresent) {
      val encoder = encoderOptional.get()
      Some(new StringTokenizer {
        override def encode(text: String): List[Token] =
          encoder.encode(text).toArray.map(tokenId => Token(tokenId)).toList
      })
    } else None
  }

  // Tiktoken (token counting)
  def countTokensWithHelper(text: String, tokenizerId: TokenizerId): Int = {
    val counter = new TokenCounter()
    counter.countTokens(text, tokenizerId)
  }
}
