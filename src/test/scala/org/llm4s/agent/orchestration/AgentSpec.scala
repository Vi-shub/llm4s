package org.llm4s.agent.orchestration

import cats.effect.IO
import cats.effect.testing.TestControl
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.concurrent.ScalaFutures
import scala.concurrent.duration._

/**
 * Unit tests for Agent abstraction following LLM4S testing patterns
 */
class AgentSpec extends AnyFlatSpec with Matchers with ScalaFutures {

  "Agent.fromFunction" should "create a working functional agent" in {
    val agent = Agent.fromFunction[String, Int]("test-agent") { input =>
      Right(input.length)
    }
    
    agent.name shouldBe "test-agent"
    agent.id should not be null
    
    val result = agent.execute("hello").unsafeRunSync()
    result shouldBe Right(5)
  }

  "Agent.fromUnsafeFunction" should "handle exceptions safely" in {
    val agent = Agent.fromUnsafeFunction[String, Int]("unsafe-agent") { input =>
      if (input.isEmpty) throw new RuntimeException("Empty input")
      else input.length
    }
    
    // Should succeed with valid input
    val successResult = agent.execute("hello").unsafeRunSync()
    successResult shouldBe Right(5)
    
    // Should handle exceptions
    val failureResult = agent.execute("").unsafeRunSync()
    failureResult.isLeft shouldBe true
  }

  "Agent.fromIO" should "handle IO operations properly" in {
    val agent = Agent.fromIO[String, String]("io-agent") { input =>
      IO.pure(Right(s"processed: $input"))
    }
    
    val result = agent.execute("test").unsafeRunSync()
    result shouldBe Right("processed: test")
  }

  "Agent.fromIO" should "handle IO failures" in {
    val agent = Agent.fromIO[String, String]("failing-io-agent") { input =>
      IO.raiseError(new RuntimeException("IO failed"))
    }
    
    val result = agent.execute("test").unsafeRunSync()
    result.isLeft shouldBe true
    result.left.get shouldBe a[OrchestrationError.NodeExecutionError]
  }

  "Agent.constant" should "always return the same value" in {
    val agent = Agent.constant[String, Int]("constant-agent", 42)
    
    agent.execute("anything").unsafeRunSync() shouldBe Right(42)
    agent.execute("different").unsafeRunSync() shouldBe Right(42)
  }

  "Agent.simpleFailure" should "always fail with the specified error" in {
    val agent = Agent.simpleFailure[String, Int]("failure-agent", "Always fails")
    
    val result = agent.execute("test").unsafeRunSync()
    result.isLeft shouldBe true
    result.left.get shouldBe a[OrchestrationError.NodeExecutionError]
  }

  "Agent composition" should "work with different input/output types" in {
    val stringToInt = Agent.fromFunction[String, Int]("string-to-int")(s => Right(s.length))
    val intToString = Agent.fromFunction[Int, String]("int-to-string")(i => Right(s"Length: $i"))
    
    // Test sequential execution (manual composition for now)
    val input = "hello world"
    val step1 = stringToInt.execute(input).unsafeRunSync()
    step1 shouldBe Right(11)
    
    val step2 = intToString.execute(step1.getOrElse(0)).unsafeRunSync()
    step2 shouldBe Right("Length: 11")
  }

  "Agent with long-running operation" should "be interruptible" in {
    val slowAgent = Agent.fromIO[String, String]("slow-agent") { input =>
      IO.sleep(5.seconds) *> IO.pure(Right(s"slow: $input"))
    }
    
    // Use TestControl for deterministic testing of time-based operations
    TestControl.executeEmbed {
      val execution = slowAgent.execute("test")
      for {
        fiber <- execution.start
        _ <- IO.sleep(1.second)
        _ <- fiber.cancel
        result <- fiber.joinWithNever.attempt
      } yield {
        result.isLeft shouldBe true // Should be cancelled
      }
    }.unsafeRunSync()
  }
}
