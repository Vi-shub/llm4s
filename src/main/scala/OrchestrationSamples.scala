// OrchestrationSamples moved from samples module

import org.llm4s.agent.orchestration._
import scala.concurrent.{ Future, ExecutionContext, Await }
import scala.concurrent.duration._

/**
 * LLM4S Multi-Agent Orchestration Samples
 * 
 * This module demonstrates Phase 1 orchestration capabilities:
 * - Typed Agent[I, O] contracts with LLM4S patterns
 * - DAG construction with compile-time type safety
 * - Topological execution with parallel nodes
 * - Basic policies (retry, timeout, fallback)
 * - Proper error handling with OrchestrationError
 * - Structured logging with MDC context
 */
object OrchestrationSamples {

  // Define data types for our workflow
  case class UserQuery(question: String, context: Option[String] = None)
  case class RetrievedContent(documents: List[String], sources: List[String])
  case class AnalysisResult(summary: String, keyPoints: List[String], sentiment: String)
  case class FinalResponse(answer: String, confidence: Double, sources: List[String])

  def createContentRetriever(): Agent[UserQuery, RetrievedContent] = {
    Agent.fromFunction("content-retriever") { query =>
      // Simulate content retrieval
      val docs = List(
        s"Document about ${query.question}: This is relevant information...",
        s"Research paper on ${query.question}: Key findings include...",
        s"Expert analysis of ${query.question}: The main points are..."
      )
      val sources = List("source1.pdf", "source2.pdf", "source3.pdf")
      Right(RetrievedContent(docs, sources))
    }
  }

  def createContentAnalyzer(): Agent[RetrievedContent, AnalysisResult] = {
    Agent.fromFunction("content-analyzer") { content =>
      if (content.documents.isEmpty) {
        Left(OrchestrationError.NodeExecutionError("analyzer", "content-analyzer", "No documents to analyze"))
      } else {
        // Simulate content analysis
        val summary = s"Analysis of ${content.documents.size} documents reveals key insights..."
        val keyPoints = List("Point 1: Important finding", "Point 2: Supporting evidence", "Point 3: Conclusion")
        val sentiment = "positive"
        Right(AnalysisResult(summary, keyPoints, sentiment))
      }
    }
  }

  def createResponseGenerator(): Agent[AnalysisResult, FinalResponse] = {
    Agent.fromFuture("response-generator") { analysis =>
      // Simulate async response generation
      Future {
        Thread.sleep(100) // Simulate processing time
        Right(FinalResponse(
          s"Based on the analysis: ${analysis.summary}. Key points: ${analysis.keyPoints.mkString(", ")}",
          0.85,
          List("aggregated-sources")
        ))
      }(ExecutionContext.global)
    }
  }

  def demoBasicDAG(): Unit = {
    println("🤖 LLM4S Multi-Agent Orchestration - Basic DAG Demo")
    println("=" * 60)

    implicit val ec: ExecutionContext = ExecutionContext.global

    try {
      println("📋 Creating execution plan...")
      
      // Create agents
      val contentRetriever = createContentRetriever()
      val contentAnalyzer = createContentAnalyzer()
      val responseGenerator = createResponseGenerator()
      
      // Add policies to agents
      val retrieverWithRetry = Policies.withRetry(contentRetriever, maxAttempts = 3, backoff = 500.millis)
      val analyzerWithTimeout = Policies.withTimeout(contentAnalyzer, timeout = 30.seconds)
      val generatorWithFallback = Policies.withFallback(responseGenerator, 
        Agent.fromFunction("simple-generator")(_ => 
          Right(FinalResponse("Simple response based on analysis", 0.5, List.empty))
        )
      )
      
      // Build the DAG plan
      val plan = Plan.builder
        .addNode(Node("retriever", retrieverWithRetry))
        .addNode(Node("analyzer", analyzerWithTimeout))  
        .addNode(Node("generator", generatorWithFallback))
        .addEdge(Edge("retrieve->analyze", 
          Node("retriever", retrieverWithRetry), 
          Node("analyzer", analyzerWithTimeout)))
        .addEdge(Edge("analyze->generate",
          Node("analyzer", analyzerWithTimeout),
          Node("generator", generatorWithFallback)))
        .build

      println(s"✅ Plan created with ${plan.nodes.size} nodes and ${plan.edges.size} edges")
      
      // Validate the plan
      plan.validate match {
        case Left(error) => 
          println(s"❌ Plan validation failed: $error")
          throw new RuntimeException(error)
        case Right(_) => 
          println("✅ Plan validation successful")
      }
      
      // Show execution order
      plan.topologicalOrder match {
        case Left(error) => println(s"❌ Topological sort failed: $error")
        case Right(order) => 
          println(s"📋 Execution order: ${order.map(_.id).mkString(" -> ")}")
      }
      
      // Show parallel batches
      plan.getParallelBatches match {
        case Left(error) => println(s"❌ Batch creation failed: $error")
        case Right(batches) =>
          println("🔄 Parallel execution batches:")
          batches.zipWithIndex.foreach { case (batch, index) =>
            println(s"   Batch ${index + 1}: ${batch.map(_.id).mkString(", ")}")
          }
      }
      
      // Execute the plan
      println("\n🚀 Executing plan...")
      
      val userQuery = UserQuery("What are the benefits of functional programming?")
      val initialInputs = Map("retriever" -> userQuery)
      
      val runner = PlanRunner()
      val resultsFuture = runner.execute(plan, initialInputs)
      
      // Wait for completion and handle results
      val results = Await.result(resultsFuture, 30.seconds)
      
      results match {
        case Right(outputs) =>
          println("✅ Execution completed successfully!")
          println("\n📊 Results:")
          outputs.foreach { case (nodeId, result) =>
            println(s"   $nodeId: ${result.toString.take(100)}...")
          }
          
        case Left(error) =>
          println(s"❌ Execution failed: $error")
      }
      
      println("\n" + "=" * 60)
      println("🎉 Basic DAG Demo completed!")
      
    } catch {
      case ex: Exception =>
        println(s"❌ Demo failed with exception: ${ex.getMessage}")
        ex.printStackTrace()
    }
  }

  def demoSimpleRAG(): Unit = {
    println("🔍 Simple RAG Pipeline Demo")
    println("=" * 40)
    
    case class Query(text: String)
    case class Documents(items: List[String])
    case class GeneratedResponse(text: String, sources: List[String])

    implicit val ec: ExecutionContext = ExecutionContext.global
    
    try {
      // Define RAG agents
      val retriever = Agent.fromFunction[Query, Documents]("rag-retriever") { query =>
        Right(Documents(List(s"Doc1 about ${query.text}", s"Doc2 about ${query.text}")))
      }
      
      val generator = Agent.fromFunction[Documents, GeneratedResponse]("rag-generator") { docs =>
        Right(GeneratedResponse(s"Generated response from ${docs.items.size} documents", docs.items))
      }
      
      // Build simple 2-node pipeline
      val plan = Plan.builder
        .addNode(Node("retrieve", retriever))
        .addNode(Node("generate", generator))
        .addEdge(Edge("retrieve->generate", 
          Node("retrieve", retriever), 
          Node("generate", generator)))
        .build
      
      println("📋 RAG plan created with 2 nodes")
      
      val runner = PlanRunner()
      val initialInputs = Map("retrieve" -> Query("machine learning"))
      
      val resultsFuture = runner.execute(plan, initialInputs)
      val results = Await.result(resultsFuture, 30.seconds)
      
      results match {
        case Right(outputs) => 
          println(s"✅ RAG Pipeline completed!")
          outputs.foreach { case (nodeId, result) =>
            println(s"   $nodeId: $result")
          }
        case Left(error) => 
          println(s"❌ RAG Pipeline failed: $error")
      }
      
    } catch {
      case ex: Exception =>
        println(s"❌ RAG Demo failed: ${ex.getMessage}")
        ex.printStackTrace()
    }
    
    println("=" * 40)
  }

  def demoPolicyIntegration(): Unit = {
    println("🛡️ Policy Integration Demo")
    println("=" * 40)
    
    implicit val ec: ExecutionContext = ExecutionContext.global
    
    case class TestInput(value: String)
    case class TestOutput(result: String)
    
    try {
      // Create a flaky agent that fails sometimes
      val flakyAgent = Agent.fromFunction[TestInput, TestOutput]("flaky-agent") { input =>
        if (scala.util.Random.nextDouble() < 0.7) { // 70% chance of failure
          Left(OrchestrationError.NodeExecutionError("flaky", "flaky-agent", "Random failure for demo"))
        } else {
          Right(TestOutput(s"Success: ${input.value}"))
        }
      }
      
      // Create a slow agent
      val slowAgent = Agent.fromFuture[TestInput, TestOutput]("slow-agent") { input =>
        Future {
          Thread.sleep(2000) // 2 seconds
          Right(TestOutput(s"Slow result: ${input.value}"))
        }
      }
      
      // Apply policies
      val retriableAgent = Policies.withRetry(flakyAgent, maxAttempts = 5, backoff = 100.millis)
      val timeoutAgent = Policies.withTimeout(slowAgent, timeout = 1.second) // Will timeout
      val fallbackAgent = Policies.withFallback(timeoutAgent,
        Agent.fromFunction[TestInput, TestOutput]("fallback")(_ => 
          Right(TestOutput("Fallback response"))
        )
      )
      
      println("🔄 Testing retry policy...")
      val retryResult = retriableAgent.execute(TestInput("retry-test"))
      Await.result(retryResult, 10.seconds) match {
        case Right(output) => println(s"   ✅ Retry succeeded: ${output.result}")
        case Left(error) => println(s"   ❌ Retry failed: $error")
      }
      
      println("⏱️ Testing timeout + fallback policy...")
      val fallbackResult = fallbackAgent.execute(TestInput("timeout-test"))
      Await.result(fallbackResult, 5.seconds) match {
        case Right(output) => println(s"   ✅ Fallback worked: ${output.result}")
        case Left(error) => println(s"   ❌ Fallback failed: $error")
      }
      
    } catch {
      case ex: Exception =>
        println(s"❌ Policy demo failed: ${ex.getMessage}")
        ex.printStackTrace()
    }
    
    println("=" * 40)
  }

  def main(args: Array[String]): Unit = {
    println("🚀 LLM4S Orchestration Samples")
    println("=" * 50)
    
    // Run all demos
    demoBasicDAG()
    println()
    demoSimpleRAG()
    println()
    demoPolicyIntegration()
    
    println("\n🎉 All orchestration samples completed!")
  }
}
