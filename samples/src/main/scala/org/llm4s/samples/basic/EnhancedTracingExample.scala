package org.llm4s.samples.basic

import org.llm4s.llmconnect.LLM
import org.llm4s.llmconnect.model._
import org.llm4s.agent.{Agent, AgentStatus}
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.tools.WeatherTool
import org.llm4s.trace.{EnhancedTracing, TracingComposer, TraceEvent, TracingMode}
import org.llm4s.error.LLMError

object EnhancedTracingExample {
  def main(args: Array[String]): Unit = {
    println("🚀 Enhanced Tracing Example")
    println("=" * 50)
    
    // Example 1: Basic enhanced tracing
    println("\n1 Basic Enhanced Tracing")
    val basicTracer = EnhancedTracing.create(TracingMode.Console)
    
    // Create some test events
    val agentEvent = TraceEvent.AgentInitialized(
      query = "What's the weather like?",
      tools = Vector("weather", "calculator")
    )
    
    val completionEvent = TraceEvent.CompletionReceived(
      id = "test-123",
      model = "gpt-4",
      toolCalls = 2,
      content = "I'll check the weather for you."
    )
    
    // Trace events
    basicTracer.traceEvent(agentEvent)
    basicTracer.traceEvent(completionEvent)
    
    // Example 2: Composed tracing (multiple tracers)
    println("\n2 Composed Tracing (Console + NoOp)")
    val consoleTracer = EnhancedTracing.create(TracingMode.Console)
    val noOpTracer = EnhancedTracing.create(TracingMode.NoOp)
    val composedTracer = TracingComposer.combine(consoleTracer, noOpTracer)
    
    composedTracer.traceEvent(agentEvent)
    
    // Example 3: Filtered tracing (only error events)
    println("\n3 Filtered Tracing (Only Errors)")
    val errorOnlyTracer = TracingComposer.filter(consoleTracer) { event =>
      event.isInstanceOf[TraceEvent.ErrorOccurred]
    }
    
    // This won't be traced (not an error)
    errorOnlyTracer.traceEvent(agentEvent)
    
    // This will be traced (is an error)
    val errorEvent = TraceEvent.ErrorOccurred(
      error = new RuntimeException("Test error"),
      context = "Enhanced tracing example"
    )
    errorOnlyTracer.traceEvent(errorEvent)
    
    // Example 4: Transformed tracing (add metadata)
    println("\n4 Transformed Tracing (Add Metadata)")
    val transformedTracer = TracingComposer.transform(consoleTracer) { event =>
      event match {
        case e: TraceEvent.CustomEvent =>
          TraceEvent.CustomEvent(
            name = s"[ENHANCED] ${e.name}",
            data = ujson.Obj.from(e.data.obj.toSeq :+ ("enhanced" -> true))
          )
        case other => other
      }
    }
    
    val customEvent = TraceEvent.CustomEvent("test", ujson.Obj("value" -> 42))
    transformedTracer.traceEvent(customEvent)
    
    // Example 5: Complex composition
    println("\n5 Complex Composition")
    val complexTracer = TracingComposer.combine(
      consoleTracer,
      TracingComposer.filter(noOpTracer) { _.isInstanceOf[TraceEvent.CompletionReceived] },
      TracingComposer.transform(consoleTracer) { event =>
        event match {
          case e: TraceEvent.TokenUsageRecorded =>
            TraceEvent.TokenUsageRecorded(
              usage = e.usage,
              model = s"[COST] ${e.model}",
              operation = e.operation
            )
          case other => other
        }
      }
    )
    
    val tokenEvent = TraceEvent.TokenUsageRecorded(
      usage = TokenUsage(10, 20, 30),
      model = "gpt-4",
      operation = "completion"
    )
    complexTracer.traceEvent(tokenEvent)
    
    // Example 6: Error handling
    println("\n6 Error Handling")
    val result = basicTracer.traceEvent(agentEvent)
    result match {
      case Right(_) => println("✅ Tracing successful")
      case Left(error) => println(s"❌ Tracing failed: ${error.message}")
    }
    
    // Example 7: Type-safe mode creation
    println("\n7 Type-Safe Mode Creation")
    val modes = Seq(TracingMode.Console, TracingMode.NoOp, TracingMode.Langfuse)
    modes.foreach { mode =>
      val tracer = EnhancedTracing.create(mode)
      println(s"Created tracer for mode: $mode")
    }
    
    // Example 8: Environment-based configuration
    println("\n8 Environment-Based Configuration")
    val envTracer = EnhancedTracing.create()
    println(s"Created tracer from environment: ${envTracer.getClass.getSimpleName}")
    
    println("\n✅ Enhanced Tracing Example Complete!")
    println("=" * 50)
    println("Key Benefits:")
    println("• Type-safe events prevent typos")
    println("• Composable tracers for complex scenarios")
    println("• Filtering and transformation capabilities")
    println("• Better error handling with Either[LLMError, Unit]")
    println("• Backward compatibility maintained")
  }
}
