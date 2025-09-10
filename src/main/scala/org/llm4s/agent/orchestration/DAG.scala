package org.llm4s.agent.orchestration

/**
 * Directed Acyclic Graph (DAG) structures for multi-agent orchestration
 */

/**
 * A node in the execution graph representing an agent with typed input/output
 */
case class Node[I, O](
  id: String,
  agent: Agent[I, O],
  description: Option[String] = None
) {
  def inputType: String = "I" // Simplified for now - runtime reflection not available in Scala 3
  def outputType: String = "O" // Simplified for now - runtime reflection not available in Scala 3
}

/**
 * A typed edge connecting two nodes in the DAG
 * Ensures compile-time type safety: output type of source must match input type of target
 */
case class Edge[A, B](
  id: String,
  source: Node[_, A], 
  target: Node[A, B],
  description: Option[String] = None
)

/**
 * A complete execution plan represented as a DAG
 */
case class Plan(
  nodes: Map[String, Node[_, _]],
  edges: List[Edge[_, _]],
  entryPoints: List[Node[_, _]], // Nodes with no incoming edges
  exitPoints: List[Node[_, _]]   // Nodes with no outgoing edges
) {
  
  /**
   * Validate that the plan forms a valid DAG (acyclic)
   */
  def validate: Either[String, Unit] = {
    // Check for cycles using DFS
    def hasCycle(visited: Set[String], recStack: Set[String], nodeId: String): Boolean = {
      if (recStack.contains(nodeId)) return true
      if (visited.contains(nodeId)) return false
      
      val outgoingEdges = edges.filter(_.source.id == nodeId)
      outgoingEdges.exists(edge => 
        hasCycle(visited + nodeId, recStack + nodeId, edge.target.id)
      )
    }
    
    val allNodeIds = nodes.keys.toSet
    val cyclic = allNodeIds.exists(nodeId => hasCycle(Set.empty, Set.empty, nodeId))
    
    if (cyclic) Left("Plan contains cycles")
    else Right(())
  }
  
  /**
   * Get topological ordering of nodes for execution
   */
  def topologicalOrder: Either[String, List[Node[_, _]]] = {
    validate.flatMap { _ =>
      val inDegree = scala.collection.mutable.Map[String, Int]()
      val adjList = scala.collection.mutable.Map[String, List[String]]()
      
      // Initialize
      nodes.keys.foreach { nodeId =>
        inDegree(nodeId) = 0
        adjList(nodeId) = List.empty
      }
      
      // Build adjacency list and in-degree count
      edges.foreach { edge =>
        adjList(edge.source.id) = edge.target.id :: adjList(edge.source.id)
        inDegree(edge.target.id) = inDegree(edge.target.id) + 1
      }
      
      // Kahn's algorithm
      val queue = scala.collection.mutable.Queue[String]()
      val result = scala.collection.mutable.ListBuffer[Node[_, _]]()
      
      // Add nodes with no incoming edges
      inDegree.filter(_._2 == 0).keys.foreach(queue.enqueue(_))
      
      while (queue.nonEmpty) {
        val nodeId = queue.dequeue()
        result += nodes(nodeId)
        
        // Remove edges and update in-degrees
        adjList(nodeId).foreach { neighbor =>
          inDegree(neighbor) = inDegree(neighbor) - 1
          if (inDegree(neighbor) == 0) {
            queue.enqueue(neighbor)
          }
        }
      }
      
      if (result.size != nodes.size) {
        Left("Topological sort failed - graph contains cycles")
      } else {
        Right(result.toList)
      }
    }
  }
  
  /**
   * Get nodes that can execute in parallel (no dependencies between them)
   * Uses level-based batching: nodes at the same dependency level can run in parallel
   */
  def getParallelBatches: Either[String, List[List[Node[_, _]]]] = {
    // Build dependency maps
    val incomingEdges = edges.groupBy(_.target.id).withDefaultValue(List.empty)
    val outgoingEdges = edges.groupBy(_.source.id).withDefaultValue(List.empty)
    
    // Calculate dependency levels using BFS
    val levels = scala.collection.mutable.Map[String, Int]()
    val queue = scala.collection.mutable.Queue[String]()
    
    // Start with nodes that have no dependencies (level 0)
    val entryNodes = nodes.keys.filter(nodeId => incomingEdges(nodeId).isEmpty)
    entryNodes.foreach { nodeId =>
      levels(nodeId) = 0
      queue.enqueue(nodeId)
    }
    
    // Process nodes level by level
    while (queue.nonEmpty) {
      val currentNodeId = queue.dequeue()
      
      // Update levels of dependent nodes
      outgoingEdges(currentNodeId).foreach { edge =>
        val targetId = edge.target.id
        val targetDependencies = incomingEdges(targetId).map(_.source.id)
        
        // Check if all dependencies of target node have been processed
        if (targetDependencies.forall(levels.contains)) {
          val maxDependencyLevel = targetDependencies.map(levels).max
          val targetLevel = maxDependencyLevel + 1
          
          if (!levels.contains(targetId)) {
            levels(targetId) = targetLevel
            queue.enqueue(targetId)
          }
        }
      }
    }
    
    // Verify all nodes have been assigned levels
    if (levels.size != nodes.size) {
      Left("Failed to assign dependency levels - graph may contain cycles")
    } else {
      // Group nodes by their dependency levels
      val batches = levels.groupBy(_._2).toSeq.sortBy(_._1).map { case (_, nodeEntries) =>
        nodeEntries.keys.map(nodeId => nodes(nodeId)).toList
      }.toList
      
      Right(batches)
    }
  }
}

object Plan {
  /**
   * Create an empty plan
   */
  def empty: Plan = Plan(Map.empty, List.empty, List.empty, List.empty)
  
  /**
   * Builder for creating plans with type safety
   */
  class PlanBuilder {
    private var nodes = Map.empty[String, Node[_, _]]
    private var edges = List.empty[Edge[_, _]]
    
    def addNode[I, O](node: Node[I, O]): PlanBuilder = {
      nodes = nodes + (node.id -> node)
      this
    }
    
    def addEdge[A, B](edge: Edge[A, B]): PlanBuilder = {
      edges = edge :: edges
      this
    }
    
    def build: Plan = {
      val incomingEdges = edges.groupBy(_.target.id)
      val outgoingEdges = edges.groupBy(_.source.id)
      
      val entryPoints = nodes.values.filter(node => !incomingEdges.contains(node.id)).toList
      val exitPoints = nodes.values.filter(node => !outgoingEdges.contains(node.id)).toList
      
      Plan(nodes, edges, entryPoints, exitPoints)
    }
  }
  
  def builder: PlanBuilder = new PlanBuilder
}
