package org.llm4s.agent.orchestration

import cats.effect.IO
import cats.effect.testing.TestControl
import cats.effect.unsafe.implicits.global
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration._

/**
 * Unit tests for agent execution policies
 */
class PoliciesSpec extends AnyFlatSpec with Matchers {

  // Test agents
  val successAgent = Agent.fromFunction[String, String]("success")(s => Right(s"success: $s"))
  
  val recoverableFailureAgent = Agent.fromFunction[String, String]("recoverable-failure") { _ =>
    Left(OrchestrationError.NodeExecutionError("test", "recoverable", "Recoverable error"))
  }
  
  val nonRecoverableFailureAgent = Agent.fromFunction[String, String]("non-recoverable-failure") { _ =>
    Left(OrchestrationError.PlanValidationError("Non-recoverable error"))
  }

  var attemptCounter = 0
  val flakyAgent = Agent.fromFunction[String, String]("flaky") { s =>
    attemptCounter += 1
    if (attemptCounter < 3) {
      Left(OrchestrationError.NodeExecutionError("flaky", "flaky", "Temporary failure"))
    } else {
      Right(s"success after retries: $s")
    }
  }

  "Policies.withRetry" should "retry recoverable failures" in {
    attemptCounter = 0 // Reset counter
    val retryAgent = Policies.withRetry(flakyAgent, maxAttempts = 3, backoff = 10.millis)
    
    val result = retryAgent.execute("test").unsafeRunSync()
    
    result.isRight shouldBe true
    result.getOrElse("") should include("success after retries")
    attemptCounter shouldBe 3
  }

  "Policies.withRetry" should "not retry non-recoverable failures" in {
    val retryAgent = Policies.withRetry(nonRecoverableFailureAgent, maxAttempts = 3)
    
    val result = retryAgent.execute("test").unsafeRunSync()
    
    result.isLeft shouldBe true
    result.left.get shouldBe a[OrchestrationError.PlanValidationError]
  }

  "Policies.withRetry" should "succeed immediately if first attempt succeeds" in {
    val retryAgent = Policies.withRetry(successAgent, maxAttempts = 3)
    
    val result = retryAgent.execute("test").unsafeRunSync()
    
    result shouldBe Right("success: test")
  }

  "Policies.withTimeout" should "timeout slow operations" in {
    val slowAgent = Agent.fromIO[String, String]("slow") { s =>
      IO.sleep(500.millis) *> IO.pure(Right(s"slow: $s"))
    }
    
    TestControl.executeEmbed {
      val timeoutAgent = Policies.withTimeout(slowAgent, 100.millis)
      
      timeoutAgent.execute("test").map { result =>
        result.isLeft shouldBe true
        result.left.get shouldBe a[OrchestrationError.AgentTimeoutError]
      }
    }.unsafeRunSync()
  }

  "Policies.withTimeout" should "succeed for fast operations" in {
    val fastAgent = Agent.fromIO[String, String]("fast") { s =>
      IO.sleep(10.millis) *> IO.pure(Right(s"fast: $s"))
    }
    
    TestControl.executeEmbed {
      val timeoutAgent = Policies.withTimeout(fastAgent, 100.millis)
      
      timeoutAgent.execute("test").map { result =>
        result shouldBe Right("fast: test")
      }
    }.unsafeRunSync()
  }

  "Policies.withFallback" should "use fallback when primary fails" in {
    val primaryAgent = recoverableFailureAgent
    val fallbackAgent = Agent.fromFunction[String, String]("fallback")(s => Right(s"fallback: $s"))
    
    val fallbackWrapped = Policies.withFallback(primaryAgent, fallbackAgent)
    
    val result = fallbackWrapped.execute("test").unsafeRunSync()
    
    result shouldBe Right("fallback: test")
  }

  "Policies.withFallback" should "use primary when it succeeds" in {
    val primaryAgent = successAgent
    val fallbackAgent = Agent.fromFunction[String, String]("fallback")(s => Right(s"fallback: $s"))
    
    val fallbackWrapped = Policies.withFallback(primaryAgent, fallbackAgent)
    
    val result = fallbackWrapped.execute("test").unsafeRunSync()
    
    result shouldBe Right("success: test")
  }

  "Policies.withFallback" should "return primary error when both fail" in {
    val primaryAgent = Agent.fromFunction[String, String]("primary") { _ =>
      Left(OrchestrationError.NodeExecutionError("primary", "primary", "Primary failure"))
    }
    val fallbackAgent = Agent.fromFunction[String, String]("fallback") { _ =>
      Left(OrchestrationError.NodeExecutionError("fallback", "fallback", "Fallback failure"))
    }
    
    val fallbackWrapped = Policies.withFallback(primaryAgent, fallbackAgent)
    
    val result = fallbackWrapped.execute("test").unsafeRunSync()
    
    result.isLeft shouldBe true
    val error = result.left.get.asInstanceOf[OrchestrationError.NodeExecutionError]
    error.nodeId shouldBe "primary" // Should return primary error
  }

  "Policies.withPolicies" should "combine multiple policies correctly" in {
    // Create an agent that fails twice then succeeds
    var policyAttempts = 0
    val testAgent = Agent.fromFunction[String, String]("policy-test") { s =>
      policyAttempts += 1
      if (policyAttempts < 3) {
        Left(OrchestrationError.NodeExecutionError("test", "test", "Temporary failure"))
      } else {
        Right(s"success: $s")
      }
    }
    
    val fallbackAgent = Agent.fromFunction[String, String]("fallback")(s => Right(s"fallback: $s"))
    
    TestControl.executeEmbed {
      policyAttempts = 0 // Reset counter
      
      val enhancedAgent = Policies.withPolicies(
        testAgent,
        retry = Some((3, 10.millis)),
        timeout = Some(1.second),
        fallback = Some(fallbackAgent)
      )
      
      enhancedAgent.execute("test").map { result =>
        result.isRight shouldBe true
        result.getOrElse("") should include("success: test")
        policyAttempts shouldBe 3
      }
    }.unsafeRunSync()
  }

  "Policies.withPolicies" should "use fallback when retries are exhausted" in {
    val alwaysFailingAgent = Agent.fromFunction[String, String]("always-failing") { _ =>
      Left(OrchestrationError.NodeExecutionError("failing", "failing", "Always fails"))
    }
    
    val fallbackAgent = Agent.fromFunction[String, String]("fallback")(s => Right(s"fallback: $s"))
    
    val enhancedAgent = Policies.withPolicies(
      alwaysFailingAgent,
      retry = Some((2, 1.milli)),
      fallback = Some(fallbackAgent)
    )
    
    val result = enhancedAgent.execute("test").unsafeRunSync()
    
    result shouldBe Right("fallback: test")
  }

  "Policy composition" should "have correct ordering (timeout -> retry -> fallback)" in {
    // This test verifies that policies are applied in the correct order
    val slowAgent = Agent.fromIO[String, String]("slow-then-success") { s =>
      IO.sleep(200.millis) *> IO.pure(Right(s"slow: $s"))
    }
    
    val fallbackAgent = Agent.fromFunction[String, String]("fallback")(s => Right(s"fallback: $s"))
    
    TestControl.executeEmbed {
      val enhancedAgent = Policies.withPolicies(
        slowAgent,
        retry = Some((2, 10.millis)),
        timeout = Some(50.millis), // Shorter than agent execution time
        fallback = Some(fallbackAgent)
      )
      
      enhancedAgent.execute("test").map { result =>
        // Should timeout on each retry attempt, then use fallback
        result shouldBe Right("fallback: test")
      }
    }.unsafeRunSync()
  }
}
