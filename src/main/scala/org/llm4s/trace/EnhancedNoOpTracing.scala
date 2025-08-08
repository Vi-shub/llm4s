package org.llm4s.trace

import org.llm4s.agent.AgentState
import org.llm4s.llmconnect.model.{TokenUsage, Completion}
import org.llm4s.error.LLMError

/**
 * Enhanced NoOp tracing implementation - does nothing but implements the interface
 */
class EnhancedNoOpTracing extends EnhancedTracing {
  def traceEvent(event: TraceEvent): Either[LLMError, Unit] = Right(())
  def traceAgentState(state: AgentState): Either[LLMError, Unit] = Right(())
  def traceToolCall(toolName: String, input: String, output: String): Either[LLMError, Unit] = Right(())
  def traceError(error: Throwable, context: String): Either[LLMError, Unit] = Right(())
  def traceCompletion(completion: Completion, model: String): Either[LLMError, Unit] = Right(())
  def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Either[LLMError, Unit] = Right(())
}
