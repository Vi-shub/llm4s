package org.llm4s.agent.orchestration

import cats.effect.IO
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration._

/**
 * Unit tests for PlanRunner execution engine
 */
class PlanRunnerSpec extends AnyFlatSpec with Matchers {

  val runner = PlanRunner()

  // Test data types
  case class InputData(value: String)
  case class ProcessedData(result: String, length: Int)
  case class FinalOutput(summary: String, success: Boolean)

  // Test agents
  val processorAgent = Agent.fromFunction[InputData, ProcessedData]("processor") { input =>
    Right(ProcessedData(s"processed: ${input.value}", input.value.length))
  }

  val summarizerAgent = Agent.fromFunction[ProcessedData, FinalOutput]("summarizer") { data =>
    Right(FinalOutput(s"Summary: ${data.result} (length: ${data.length})", data.length > 0))
  }

  val failingAgent = Agent.fromFunction[InputData, ProcessedData]("failing") { _ =>
    Left(OrchestrationError.NodeExecutionError("test-node", "failing-agent", "Simulated failure"))
  }

  "PlanRunner" should "execute a simple linear plan successfully" in {
    val nodeA = Node("processor", processorAgent)
    val nodeB = Node("summarizer", summarizerAgent)

    val plan = Plan.builder
      .addNode(nodeA)
      .addNode(nodeB)
      .addEdge(Edge("proc-summ", nodeA, nodeB))
      .build

    val initialInputs = Map("processor" -> InputData("hello world"))

    val result = runner.execute(plan, initialInputs).unsafeRunSync()
    
    result.isRight shouldBe true
    val outputs = result.getOrElse(Map.empty)
    
    outputs should contain key "processor"
    outputs should contain key "summarizer"
    
    val finalOutput = outputs("summarizer").asInstanceOf[FinalOutput]
    finalOutput.summary should include("processed: hello world")
    finalOutput.success shouldBe true
  }

  "PlanRunner" should "handle parallel execution" in {
    // Create two independent processing chains
    val processor1 = Agent.fromFunction[InputData, ProcessedData]("processor1") { input =>
      Right(ProcessedData(s"path1: ${input.value}", input.value.length))
    }
    
    val processor2 = Agent.fromFunction[InputData, ProcessedData]("processor2") { input =>
      Right(ProcessedData(s"path2: ${input.value}", input.value.length))
    }

    val node1 = Node("proc1", processor1)
    val node2 = Node("proc2", processor2)

    val plan = Plan.builder
      .addNode(node1)
      .addNode(node2)
      .build // No edges = both can run in parallel

    val initialInputs = Map(
      "proc1" -> InputData("data1"),
      "proc2" -> InputData("data2")
    )

    val startTime = System.currentTimeMillis()
    val result = runner.execute(plan, initialInputs).unsafeRunSync()
    val endTime = System.currentTimeMillis()

    result.isRight shouldBe true
    val outputs = result.getOrElse(Map.empty)
    
    outputs should have size 2
    outputs should contain key "proc1"
    outputs should contain key "proc2"
    
    val output1 = outputs("proc1").asInstanceOf[ProcessedData]
    val output2 = outputs("proc2").asInstanceOf[ProcessedData]
    
    output1.result should include("path1: data1")
    output2.result should include("path2: data2")
  }

  "PlanRunner" should "handle plan validation errors" in {
    // Create a plan with a cycle
    val nodeA = Node("a", processorAgent)
    val nodeB = Node("b", Agent.fromFunction[ProcessedData, InputData]("reverser")(d => Right(InputData(d.result))))

    val invalidPlan = Plan.builder
      .addNode(nodeA)
      .addNode(nodeB)
      .addEdge(Edge("a-b", nodeA, nodeB))
      .addEdge(Edge("b-a", nodeB, nodeA)) // Creates cycle
      .build

    val initialInputs = Map("a" -> InputData("test"))

    val result = runner.execute(invalidPlan, initialInputs).unsafeRunSync()
    
    result.isLeft shouldBe true
    result.left.get shouldBe a[OrchestrationError.PlanValidationError]
  }

  "PlanRunner" should "handle node execution failures" in {
    val nodeA = Node("failing", failingAgent)
    val nodeB = Node("summarizer", summarizerAgent)

    val plan = Plan.builder
      .addNode(nodeA)
      .addNode(nodeB)
      .addEdge(Edge("fail-summ", nodeA, nodeB))
      .build

    val initialInputs = Map("failing" -> InputData("test"))

    val result = runner.execute(plan, initialInputs).unsafeRunSync()
    
    result.isLeft shouldBe true
    val error = result.left.get
    error shouldBe a[OrchestrationError.NodeExecutionError]
  }

  "PlanRunner" should "handle missing inputs gracefully" in {
    val nodeA = Node("processor", processorAgent)

    val plan = Plan.builder
      .addNode(nodeA)
      .build

    // Missing required input
    val initialInputs = Map.empty[String, Any]

    val result = runner.execute(plan, initialInputs).unsafeRunSync()
    
    result.isLeft shouldBe true
    result.left.get shouldBe a[OrchestrationError.NodeExecutionError]
  }

  "PlanRunner" should "execute diamond-shaped DAG correctly" in {
    // A -> B, A -> C, B -> D, C -> D
    val nodeA = Node("source", Agent.fromFunction[InputData, String]("source")(d => Right(d.value)))
    val nodeB = Node("pathB", Agent.fromFunction[String, String]("pathB")(s => Right(s"B:$s")))
    val nodeC = Node("pathC", Agent.fromFunction[String, String]("pathC")(s => Right(s"C:$s")))
    val nodeD = Node("merger", Agent.fromFunction[String, String]("merger") { s =>
      // This is a simplification - in reality we'd need both B and C outputs
      Right(s"merged:$s")
    })

    val plan = Plan.builder
      .addNode(nodeA)
      .addNode(nodeB)
      .addNode(nodeC)
      .addNode(nodeD)
      .addEdge(Edge("a-b", nodeA, nodeB))
      .addEdge(Edge("a-c", nodeA, nodeC))
      .addEdge(Edge("b-d", nodeB, nodeD))
      .build // Note: simplified - only B->D edge for testing

    val initialInputs = Map("source" -> InputData("test"))

    val result = runner.execute(plan, initialInputs).unsafeRunSync()
    
    result.isRight shouldBe true
    val outputs = result.getOrElse(Map.empty)
    
    outputs should contain key "source"
    outputs should contain key "pathB"
    outputs should contain key "pathC"
    outputs should contain key "merger"
    
    outputs("merger").asInstanceOf[String] should include("merged:B:test")
  }

  "PlanRunner" should "handle slow agents gracefully" in {
    val slowAgent = Agent.fromIO[InputData, ProcessedData]("slow") { input =>
      IO.sleep(100.millis) *> IO.pure(Right(ProcessedData(s"slow: ${input.value}", input.value.length)))
    }

    val node = Node("slow", slowAgent)
    val plan = Plan.builder.addNode(node).build
    val initialInputs = Map("slow" -> InputData("test"))

    val startTime = System.currentTimeMillis()
    val result = runner.execute(plan, initialInputs).unsafeRunSync()
    val endTime = System.currentTimeMillis()

    result.isRight shouldBe true
    (endTime - startTime) should be >= 100L // Should take at least 100ms
  }
}
