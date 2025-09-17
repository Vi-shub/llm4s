package org.llm4s.agent.orchestration

import org.llm4s.types.{ Result, AsyncResult, PlanId }
import org.slf4j.{ LoggerFactory, MDC }
import scala.concurrent.{ Future, ExecutionContext }

/**
 * Executes DAG plans with topological ordering and parallel execution.
 *
 * Follows LLM4S patterns:
 * - Uses AsyncResult[_] (Future[Result[_]]) for async operations
 * - Structured logging with MDC context
 * - Proper error types from OrchestrationError
 * - Result.safely for exception handling
 * - Standard Future combinators
 */
class PlanRunner {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Execute a plan with the given initial inputs
   *
   * @param plan The execution plan
   * @param initialInputs Map from node ID to input value
   * @param ec Execution context for async operations
   * @return AsyncResult with final results from all exit points
   */
  def execute(plan: Plan, initialInputs: Map[String, Any])(implicit
    ec: ExecutionContext
  ): AsyncResult[Map[String, Any]] = {
    val planId = PlanId.generate()

    // Set up MDC context for tracing
    MDC.put("planId", planId.value)
    MDC.put("component", "plan-runner")
    logger.info("Starting plan execution with {} nodes and {} edges", plan.nodes.size, plan.edges.size)

    val resultFuture = executeWithContext(plan, initialInputs, planId)

    // Ensure cleanup happens regardless of success/failure
    resultFuture.andThen { case _ =>
      logger.info("Plan execution completed")
      MDC.remove("planId")
      MDC.remove("component")
    }
  }

  private def executeWithContext(plan: Plan, initialInputs: Map[String, Any], planId: PlanId)(implicit
    ec: ExecutionContext
  ): AsyncResult[Map[String, Any]] =
    // Validate plan structure
    plan.validate match {
      case Left(error) =>
        logger.error("Plan validation failed: {}", error)
        Future.successful(Left(OrchestrationError.PlanValidationError(error, planId.value)))
      case Right(_) =>
        logger.debug("Plan validation successful")

        // Get execution batches for parallel processing
        plan.getParallelBatches match {
          case Left(error) =>
            logger.error("Failed to create execution batches: {}", error)
            Future.successful(
              Left(OrchestrationError.PlanValidationError(s"Batch creation failed: $error", planId.value))
            )
          case Right(batches) =>
            logger.debug("Created {} execution batches", batches.size)

            // Execute batches sequentially, nodes within batches in parallel
            executeBatches(batches, initialInputs, plan, planId)
              .map {
                case Right(results) =>
                  logger.info("Plan execution completed successfully with {} results", results.size)
                  Right(results)
                case Left(error) =>
                  logger.error("Plan execution failed: {}", error.toString)
                  Left(error)
              }
              .recover { error =>
                logger.error("Plan execution failed with unexpected error", error)
                Left(
                  OrchestrationError.PlanExecutionError.withCause(
                    s"Unexpected execution failure: ${error.getMessage}",
                    error,
                    planId.value
                  )
                )
              }
        }
    }

  private def executeBatches(
    batches: List[List[Node[_, _]]],
    initialInputs: Map[String, Any],
    plan: Plan,
    planId: PlanId
  )(implicit ec: ExecutionContext): AsyncResult[Map[String, Any]] = {

    def executeBatch(
      batch: List[Node[_, _]],
      currentResults: Map[String, Any],
      batchIndex: Int
    ): Future[Result[Map[String, Any]]] = {
      logger.debug("Executing batch {} with {} nodes: {}", batchIndex, batch.size, batch.map(_.id).mkString(", "))

      // Execute all nodes in this batch in parallel
      val nodeExecutions = batch.map { node =>
        executeNode(node, currentResults, plan, planId).map(result => result.map(r => node.id -> r))
      }

      // Wait for all parallel executions to complete
      Future
        .sequence(nodeExecutions)
        .map { results =>
          // Collect successes and failures
          val successes = results.collect { case Right((id, result)) => id -> result }
          val failures  = results.collect { case Left(error) => error }

          if (failures.nonEmpty) {
            logger.error("Batch {} had {} failures", batchIndex, failures.size)
            Left(failures.head) // Return first failure
          } else {
            val newResults = currentResults ++ successes.toMap
            logger.debug("Batch {} completed, total results: {}", batchIndex, newResults.size)
            Right(newResults)
          }
        }
        .recover { error =>
          logger.error("Batch {} failed with exception", batchIndex, error)
          Left(
            OrchestrationError.PlanExecutionError(
              s"Batch $batchIndex execution failed: ${error.getMessage}",
              planId.value
            )
          )
        }
    }

    // Execute batches sequentially, accumulating results
    def executeBatchesSequentially(
      remainingBatches: List[(List[Node[_, _]], Int)],
      currentResults: Map[String, Any]
    ): Future[Result[Map[String, Any]]] =
      remainingBatches match {
        case Nil => Future.successful(Right(currentResults))
        case (batch, index) :: tail =>
          executeBatch(batch, currentResults, index + 1).flatMap {
            case Right(newResults) => executeBatchesSequentially(tail, newResults)
            case Left(error)       => Future.successful(Left(error))
          }
      }

    executeBatchesSequentially(batches.zipWithIndex, initialInputs)
  }

  /**
   * Resolve input for a node based on DAG dependencies
   */
  private def resolveNodeInput(node: Node[_, _], availableInputs: Map[String, Any], plan: Plan): Option[Any] = {
    // Find incoming edges to this node
    val incomingEdges = plan.edges.filter(_.target.id == node.id)

    if (incomingEdges.isEmpty) {
      // This is an entry node - look for initial input by node ID
      availableInputs.get(node.id)
    } else {
      // This node depends on other nodes - find the input from dependencies
      // Iterate through all incoming edges to find the first one with a successful output
      // This allows downstream nodes to run when at least one dependency succeeds
      incomingEdges
        .map(edge => availableInputs.get(edge.source.id))
        .collectFirst { case Some(value) => value }
    }
  }

  private def executeNode(
    node: Node[_, _],
    availableInputs: Map[String, Any],
    plan: Plan,
    @annotation.unused planId: PlanId
  )(implicit ec: ExecutionContext): AsyncResult[Any] = {
    // Set up node-specific MDC context
    MDC.put("nodeId", node.id)
    MDC.put("nodeName", node.agent.name)
    MDC.put("agentId", node.agent.id.value)

    logger.debug("Starting node execution")

    // Determine input for this node
    val nodeInput = resolveNodeInput(node, availableInputs, plan)

    val resultFuture = nodeInput match {
      case Some(input) =>
        logger.debug("Node input available, type: {}", input.getClass.getSimpleName)

        // Execute the agent with proper type safety
        // Note: Due to type erasure, we need to use controlled casting here
        // This is a necessary limitation of the current design with type-erased nodes
        val agent = node.agent.asInstanceOf[Agent[Any, Any]]

        agent.execute(input).recover { error =>
          logger.error("Node execution failed with unexpected error", error)
          Left(
            OrchestrationError.NodeExecutionError(
              node.id,
              node.agent.name,
              s"Unexpected execution error: ${error.getMessage}",
              error
            )
          )
        }

      case None =>
        logger.error("No input available for node")
        Future.successful(
          Left(
            OrchestrationError.NodeExecutionError(
              node.id,
              node.agent.name,
              "No input available for node execution"
            )
          )
        )
    }

    // Ensure cleanup happens regardless of success/failure
    resultFuture.andThen { case _ =>
      MDC.remove("nodeId")
      MDC.remove("nodeName")
      MDC.remove("agentId")
    }
  }
}

object PlanRunner {
  def apply(): PlanRunner = new PlanRunner()
}
