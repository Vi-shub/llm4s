// LLMIntegrationDemo.scala (This is just to get idea on how to generalise the apis/endpoints for specific roles)
// More config file are to be added 
import scala.util.{Try, Success, Failure} // (demo imports)
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

// === Shared Models ===
sealed trait Role { def name: String }
case object System extends Role { val name = "system" }
case object User   extends Role { val name = "user" }
case object Assistant extends Role { val name = "assistant" }

case class ChatMessage(role: Role, content: String)
case class ChatRequest(
  messages: List[ChatMessage],
  temperature: Double = 0.7,
  model: String = "gpt-4",
  config: Map[String, Any] = Map.empty
)

case class ChatResponse(content: String, tokensUsed: Option[Int] = None)


// === Trait for LLM Providers ===
trait LLMProvider {
  def name: String
  def chat(request: ChatRequest): Future[ChatResponse]
}

// === Provider: OpenAI (Mocked) ===
class OpenAIProvider(apiKey: String) extends LLMProvider {
  override val name: String = "OpenAI"

  override def chat(request: ChatRequest): Future[ChatResponse] = Future {
    println(s"[OpenAI API] -> Model: ${request.model}, Temperature: ${request.temperature}")
    request.messages.foreach(m => println(s"${m.role.name}: ${m.content}"))

    ChatResponse(
      content = "OpenAI (GPT-4) says: Scala is the best Language.",
      tokensUsed = Some(30)
    )
  }
}


// === Provider: Anthropic (Mocked Claude) ===
class AnthropicProvider(apiKey: String) extends LLMProvider {
  override val name: String = "Anthropic Claude"

  override def chat(request: ChatRequest): Future[ChatResponse] = Future {
    println(s"[Claude API] -> Model: ${request.model}")
    println("Sending structured prompt:")
    val formattedPrompt = request.messages.map {
      case ChatMessage(role, text) => s"${role.name.toUpperCase}: $text"
    }.mkString("\n")
    println(formattedPrompt)

    ChatResponse(
      content = "Claude responds: Scala is the best Language.",
      tokensUsed = Some(28)
    )
  }
}


// === Factory for Creating Providers ===
object LLMProviderFactory {
  def getProvider(name: String, apiKey: String): Try[LLMProvider] = name.toLowerCase match {
    case "openai"    => Success(new OpenAIProvider(apiKey))
    case "anthropic" => Success(new AnthropicProvider(apiKey))
    case unknown     => Failure(new Exception(s"Provider '$unknown' not supported yet.")) // for special tasks only
  }
}


// === Usage / Entry Point ===
object LLMIntegrationDemo extends App {

  // Configurable parameters
  val selectedProvider = "openai" // mocked demo provider 
  val apiKey = "fake-key" // mocked demo key 

  // Create the provider instance
  val maybeProvider = LLMProviderFactory.getProvider(selectedProvider, apiKey)

  maybeProvider match {
    case Success(provider) =>
      println(s"Using provider: ${provider.name}\n")

      val chatRequest = ChatRequest(
        messages = List(
          ChatMessage(System, "You are a helpful assistant."),
          ChatMessage(User, "What is Scala?")
        ),
        temperature = 0.3,
        model = if (selectedProvider == "openai") "gpt-4" else "claude-3-opus"
      )

      provider.chat(chatRequest).foreach { response =>
        println(s"\nResponse: ${response.content}")
        response.tokensUsed.foreach(tokens => println(s"🔢 Tokens used: $tokens"))
      }

    case Failure(exception) =>
      println(s"Failed to initialize provider: ${exception.getMessage}")
  }
}
