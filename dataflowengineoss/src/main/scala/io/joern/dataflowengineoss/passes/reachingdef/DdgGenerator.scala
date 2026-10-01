package io.joern.dataflowengineoss.passes.reachingdef

import io.joern.dataflowengineoss.{
  firstIdentifierFromCapturedScopes,
  globalFromLiteral,
  identifierToFirstUsages,
  isDart
}
import io.joern.dataflowengineoss.queryengine.AccessPathUsage.toTrackedBaseAndAccessPathSimple
import io.joern.dataflowengineoss.queryengine.OutputChannel
import io.joern.dataflowengineoss.semanticsloader.Semantics
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.codepropertygraph.generated.{ControlStructureTypes, EdgeTypes, Operators}
import io.shiftleft.semanticcpg.accesspath.MatchResult
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.codepropertygraph.generated.DiffGraphBuilder

import scala.collection.{Set, mutable}

/** Creation of data dependence edges based on solution of the ReachingDefProblem.
  */
class DdgGenerator(semantics: Semantics) {

  implicit val s: Semantics = semantics

  private def returnsReachingExit(method: Method): Set[Return] = {
    val reachable = mutable.HashSet.empty[CfgNode]
    val forward   = mutable.ArrayDeque[CfgNode](method)
    while (forward.nonEmpty) {
      val node = forward.removeHead()
      if (reachable.add(node)) forward ++= node._cfgOut.cast[CfgNode]
    }
    reachable.collect {
      case returned: Return if ExitRouting.reaches(returned, method.methodReturn, method) => returned
    }.toSet
  }

  /** Once reaching definitions have been computed, we create a data dependence graph by checking which reaching
    * definitions are relevant, meaning that a symbol is propagated that is used by the target node.
    *
    * @param dstGraph
    *   the diff graph to add edges to
    * @param problem
    *   the reaching definition problem
    * @param solution
    *   the solution to `problem`
    */
  def addReachingDefEdges(
    dstGraph: DiffGraphBuilder,
    method: Method,
    problem: DataFlowProblem[CfgNode, mutable.BitSet],
    solution: Solution[CfgNode, mutable.BitSet]
  ): Unit = {
    implicit val implicitDst: DiffGraphBuilder = dstGraph

    val numberToNode = problem.flowGraph.asInstanceOf[ReachingDefFlowGraph].numberToNode
    val in           = solution.in
    val gen          = solution.problem.transferFunction.asInstanceOf[ReachingDefTransferFunction].gen

    val allNodes      = in.keys.toList
    val usageAnalyzer = new UsageAnalyzer(problem, in)
    val dart          = isDart(method)
    val exitReturns   = if (dart) returnsReachingExit(method) else Set.empty[Return]

    /** Add an edge from the entry node to each node that does not have other incoming definitions.
      */
    def addEdgesFromEntryNode(): Unit = {
      // Add edges from the entry node
      allNodes
        .filter(n => isDdgNode(n) && usageAnalyzer.usedIncomingDefs(n).isEmpty)
        .foreach { node =>
          addEdge(method, node)
        }
    }

    // This handles `foo(new Bar()) or return new Bar()`
    def addEdgeForBlock(block: Block, towards: CfgNode): Unit = {
      block.astChildren.lastOption match {
        case None                   => // Do nothing
        case Some(node: Identifier) =>
          val edgesToAdd = in(node).toList
            .flatMap(numberToNode.get)
            .filter(inDef => usageAnalyzer.isUsing(node, inDef))
            .collect {
              case identifier: Identifier               => identifier
              case call: Call                           => call
              case parameter: MethodParameterIn if dart => parameter
            }
          edgesToAdd.foreach { inNode =>
            addEdge(inNode, block, nodeToEdgeLabel(inNode))
          }
          if (edgesToAdd.nonEmpty) {
            addEdge(block, towards)
          }
        case Some(node: Call) =>
          addEdge(node, block, nodeToEdgeLabel(node))
          addEdge(block, towards)
        case Some(node: Block) if dart =>
          addEdgeForBlock(node, block)
          addEdge(block, towards)
        case _ => // Do nothing
      }
    }

    /** Adds incoming edges to arguments of call sites, including edges between arguments of the same call site.
      */
    def addEdgesToCallSite(call: Call): Unit = {
      if (
        dart && call.name == Operators.fieldAccess &&
        !call.inCall.exists(parent => parent.name == Operators.assignment && call.argumentIndex == 1)
      ) {
        call.argumentOption(1).collect { case base: Call => addEdge(base, call, nodeToEdgeLabel(base)) }
      }
      // Edges between arguments of call sites
      usageAnalyzer.usedIncomingDefs(call).foreach { case (use, ins) =>
        ins.foreach { in =>
          val inNode = numberToNode(in)
          if (inNode != use) {
            addEdge(inNode, use, nodeToEdgeLabel(inNode))
          }
        }
      }

      // For all calls, assume that input arguments
      // taint corresponding output arguments
      // and the return value. We filter invalid
      // edges at query time (according to the given semantic).
      usageAnalyzer.uses(call).foreach { use =>
        gen(call).foreach { g =>
          val genNode = numberToNode(g)
          if (use != genNode && isDdgNode(use)) {
            addEdge(use, genNode, nodeToEdgeLabel(use))
          }
        }
      }

      // This handles `foo(new Bar())`, which is lowered to
      // `foo({Bar tmp = Bar.alloc(); tmp.init(); tmp})`
      call.argument.isBlock.foreach { block => addEdgeForBlock(block, call) }
    }

    def addEdgesToReturn(ret: Return): Unit = {
      // This handles `return new Bar()`, which is lowered to
      // `return {Bar tmp = Bar.alloc(); tmp.init(); tmp}`
      usageAnalyzer.uses(ret).collectAll[Block].foreach(block => addEdgeForBlock(block, ret))
      usageAnalyzer.usedIncomingDefs(ret).foreach { case (use: CfgNode, inElements) =>
        addEdge(use, ret, use.code)
        inElements
          .filterNot(x => numberToNode.get(x).contains(use))
          .flatMap(numberToNode.get)
          .foreach { inElemNode =>
            addEdge(inElemNode, use, nodeToEdgeLabel(inElemNode))
          }
        if (inElements.isEmpty) {
          addEdge(method, ret)
        }
      }
      if (!dart || exitReturns.contains(ret)) addEdge(ret, method.methodReturn, "<RET>")
    }

    def addEdgesToThrowOperands(thrown: ControlStructure): Unit = {
      usageAnalyzer.uses(thrown).collectAll[Block].foreach(block => addEdgeForBlock(block, thrown))
      usageAnalyzer.usedIncomingDefs(thrown).foreach { case (use, definitions) =>
        definitions.flatMap(numberToNode.get).filterNot(_ == use).foreach { definition =>
          addEdge(definition, use, nodeToEdgeLabel(definition))
        }
      }
    }

    def addCaughtValueEdges(): Unit = {
      val channels = allNodes.collect {
        case call: Call if call.name == "<operator>.caughtException" || call.name == "<operator>.caughtStackTrace" =>
          call
      }
      if (channels.nonEmpty) {
        val routedExceptions = allNodes
          .collect {
            case call: Call                                                                             => call
            case thrown: ControlStructure if thrown.controlStructureType == ControlStructureTypes.THROW => thrown
          }
          .flatMap { origin =>
            val owner = ExitRouting.handlerOwner(origin, method)
            // Cleanup-local handlers consume their own exceptions, not the suspended outer payload.
            owner.toList
              .flatMap(_._catchBodyOut.cast[AstNode])
              .collect { case handler: ControlStructure => handler }
              .flatMap { handler =>
                handler.condition.isLiteral
                  .codeExact("true")
                  .filter(ExitRouting.reaches(origin, _, method))
                  .map(_ => handler -> origin)
              }
          }
          .groupMap(_._1)(_._2)
        channels.foreach { channel =>
          val index   = if (channel.name == "<operator>.caughtException") 1 else 2
          val handler =
            Iterator.single(channel).inAstMinusLeaf.takeWhile(_ != method).isControlStructure.isCatch.headOption
          for {
            caught <- handler
            origin <- routedExceptions.getOrElse(caught, Nil)
          } origin match {
            case call: Call =>
              val output = if (index == 1) OutputChannel.ExceptionValue else OutputChannel.ExceptionStack
              dstGraph.addEdge(call, channel, EdgeTypes.REACHING_DEF, output.edgeLabel)
            case thrown: ControlStructure =>
              thrown._argumentOut.cast[Expression].filter(_.argumentIndex == index).foreach {
                case block: Block => addEdgeForBlock(block, channel)
                case operand      => addEdge(operand, channel, nodeToEdgeLabel(operand))
              }
            case _ =>
          }
        }
      }
    }

    def addEdgesToMethodParameterOut(paramOut: MethodParameterOut): Unit = {
      // There is always an edge from the method input parameter
      // to the corresponding method output parameter as modifications
      // of the input parameter only affect a copy.
      paramOut.start.paramIn.foreach { paramIn =>
        addEdge(paramIn, paramOut, paramIn.name)
      }
      usageAnalyzer.usedIncomingDefs(paramOut).foreach { case (_, inElements) =>
        inElements.foreach { inElement =>
          val inElemNode = numberToNode(inElement)
          val edgeLabel  = nodeToEdgeLabel(inElemNode)
          addEdge(inElemNode, paramOut, edgeLabel)
        }
      }
    }

    def addEdgesToExitNode(exitNode: MethodReturn): Unit = {
      in(exitNode).foreach { i =>
        val iNode = numberToNode(i)
        addEdge(iNode, exitNode, nodeToEdgeLabel(iNode))
      }
    }

    /** This is part of the Lone-identifier optimization: as we remove lone identifiers from `gen` sets, we must now
      * retrieve them and create an edge from each lone identifier to the exit node.
      */
    def addEdgesFromLoneIdentifiersToExit(method: Method): Unit = {
      val numberToNode     = problem.flowGraph.asInstanceOf[ReachingDefFlowGraph].numberToNode
      val exitNode         = method.methodReturn
      val transferFunction = solution.problem.transferFunction.asInstanceOf[OptimizedReachingDefTransferFunction]
      val genOnce          = transferFunction.loneIdentifiers
      genOnce.foreach { case (_, defs) =>
        defs.foreach { d =>
          val dNode = numberToNode(d)
          addEdge(dNode, exitNode, nodeToEdgeLabel(dNode))
        }
      }
    }

    def addEdgesToCapturedIdentifiersAndParameters(): Unit = {
      val identifierDestPairs =
        method._identifierViaContainsOut.flatMap { identifier =>
          identifierToFirstUsages(identifier).map(usage => (identifier, usage))
        }.l

      identifierDestPairs
        .foreach { case (src, dst) =>
          addEdge(src, dst, nodeToEdgeLabel(src))
        }
      method.parameter.foreach { param =>
        val identifiers =
          if (dart) firstIdentifierFromCapturedScopes(param, includeModeledInputs = true)
          else param.capturedByMethodRef.referencedMethod.ast.isIdentifier.l
        identifiers.foreach { identifier =>
          addEdge(param, identifier, nodeToEdgeLabel(param))
        }
      }

      // NOTE: Below connects REACHING_DEF edges between method boundaries of closures. In the case of PARENT -> CHILD
      // this brings no inconsistent flows, but from CHILD -> PARENT we have observed inconsistencies. This form of
      // modelling data-flow is unsound as the engine assumes REACHING_DEF edges are intraprocedural.
      // See PR #3735 on Joern for details
      val globalIdentifiers =
        (method._callViaContainsOut ++ method._returnViaContainsOut).ast.isLiteral
          .flatMap(globalFromLiteral(_))
          .collectAll[Identifier]
          .l
      globalIdentifiers
        .foreach { global =>
          identifierToFirstUsages(global).map { identifier =>
            addEdge(global, identifier, nodeToEdgeLabel(global))
          }
        }
    }

    addEdgesFromEntryNode()
    allNodes.foreach {
      case call: Call                   => addEdgesToCallSite(call)
      case ret: Return                  => addEdgesToReturn(ret)
      case paramOut: MethodParameterOut => addEdgesToMethodParameterOut(paramOut)
      case thrown: ControlStructure if dart && thrown.controlStructureType == ControlStructureTypes.THROW =>
        addEdgesToThrowOperands(thrown)
      case _ =>
    }

    if (dart) addCaughtValueEdges()
    addEdgesToCapturedIdentifiersAndParameters()
    addEdgesToExitNode(method.methodReturn)
    addEdgesFromLoneIdentifiersToExit(method)
    addAddressDerefEdges(method)
  }

  /** Intraprocedural only: one address-of operand per Local/param; no heap or interprocedural tracking. */
  private def addAddressDerefEdges(method: Method)(implicit dstGraph: DiffGraphBuilder): Unit = {
    val addressOfMap: Map[Long, CfgNode] = method.ast.isCall
      .nameExact(Operators.assignment)
      .flatMap { assign =>
        val lhs = assign.argumentOption(1).collect { case id: Identifier => id }
        val rhs = assign.argumentOption(2).collect { case c: Call if c.name == Operators.addressOf => c }
        for {
          id      <- lhs
          addr    <- rhs
          decl    <- id.refsTo.collect { case local: Local => local; case param: MethodParameterIn => param }.headOption
          operand <- addr.argumentOption(1).collect {
            case operand: Identifier                                                                   => operand
            case c: Call if c.name == Operators.indirectIndexAccess || c.name == Operators.indexAccess => c
          }
        } yield decl.id -> operand
      }
      .groupBy(_._1)
      .collect { case (declId, pairs) if pairs.size == 1 => declId -> pairs.head._2 }
      .toMap

    if (addressOfMap.isEmpty)
      return

    // Only matches `*identifier` where identifier refs a Local or MethodParameterIn.
    // Skips complex nesting like `(*p)`, `*&x`, or `*f()`.
    method.ast.isCall.nameExact(Operators.indirection).foreach { derefCall =>
      derefCall.argumentOption(1).collect { case id: Identifier => id }.foreach { id =>
        id.refsTo
          .collect { case local: Local => local; case param: MethodParameterIn => param }
          .headOption
          .flatMap(decl => addressOfMap.get(decl.id))
          .foreach(sourceNode => addEdge(sourceNode, derefCall, nodeToEdgeLabel(sourceNode)))
      }
    }
  }

  private def addEdge(fromNode: CfgNode, toNode: CfgNode, variable: String = "")(implicit
    dstGraph: DiffGraphBuilder
  ): Unit = {
    if (fromNode.isInstanceOf[Unknown] || toNode.isInstanceOf[Unknown])
      return

    (fromNode, toNode) match {
      case (parentNode: CfgNode, childNode: CfgNode) if EdgeValidator.isValidEdge(childNode, parentNode) =>
        dstGraph.addEdge(fromNode, toNode, EdgeTypes.REACHING_DEF, variable)
      case _ =>

    }
  }

  /** There are a few node types that (a) are not to be considered in the DDG, or (b) are not standalone DDG nodes, or
    * (c) have a special meaning in the DDG. This function indicates whether the given node is just a regular DDG node
    * instead.
    */
  private def isDdgNode(x: CfgNode): Boolean = {
    x match {
      case _: Method           => false
      case _: ControlStructure => false
      case _: FieldIdentifier  => false
      case _: JumpTarget       => false
      case _: MethodReturn     => false
      case _                   => true
    }
  }

  private def nodeToEdgeLabel(node: CfgNode): String = {
    node match {
      case n: MethodParameterIn => n.name
      case n: CfgNode           => n.code
    }
  }
}

/** Upon calculating reaching definitions, we find ourselves with a set of incoming definitions `in(n)` for each node
  * `n` of the flow graph. This component determines those of the incoming definitions that are relevant as the value
  * they define is actually used by `n`.
  */
private class UsageAnalyzer(problem: DataFlowProblem[CfgNode, mutable.BitSet], in: Map[CfgNode, Set[Definition]]) {

  val numberToNode: Map[Definition, CfgNode] = problem.flowGraph.asInstanceOf[ReachingDefFlowGraph].numberToNode

  private val allNodes     = in.keys.toList
  private val containerSet =
    Set(Operators.fieldAccess, Operators.indexAccess, Operators.indirectIndexAccess, Operators.indirectFieldAccess)
  private val indirectionAccessSet = Set(Operators.addressOf, Operators.indirection)
  private val aliases = new ReferenceAliases(Some(problem.flowGraph.asInstanceOf[ReachingDefFlowGraph].method))
  private val dart    = isDart(problem.flowGraph.asInstanceOf[ReachingDefFlowGraph].method)
  val usedIncomingDefs: Map[CfgNode, Map[CfgNode, Set[Definition]]] = initUsedIncomingDefs()

  def initUsedIncomingDefs(): Map[CfgNode, Map[CfgNode, Set[Definition]]] = {
    allNodes.map { node =>
      node -> usedIncomingDefsForNode(node)
    }.toMap
  }

  private def usedIncomingDefsForNode(node: CfgNode): Map[CfgNode, Set[Definition]] = {
    uses(node).map { use =>
      use -> in(node).filter { inElement =>
        val inElemNode = numberToNode(inElement)
        isUsing(use, inElemNode)
      }
    }.toMap
  }

  def isUsing(use: CfgNode, inElemNode: CfgNode): Boolean =
    sameVariable(use, inElemNode) || aliases.sameReference(use, inElemNode) ||
      isContainer(use, inElemNode) || isPart(use, inElemNode) || isAlias(use, inElemNode)

  /** Determine whether the node `use` describes a container for `inElement`, e.g., use = `ptr` while inElement =
    * `ptr->foo`.
    */
  private def isContainer(use: CfgNode, inElement: CfgNode): Boolean = {
    inElement match {
      case call: Call if containerSet.contains(call.name) =>
        call.argument.headOption.exists { base =>
          nodeToString(use) == nodeToString(base) || aliases.sameReference(use, base)
        }
      case _ => false
    }
  }

  /** Determine whether `use` is a part of `inElement`, e.g., use = `argv[0]` while inElement = `argv`
    */
  private def isPart(use: CfgNode, inElement: CfgNode): Boolean = {
    use match {
      case call: Call if containerSet.contains(call.name) =>
        inElement match {
          case param: MethodParameterIn =>
            call.argument.headOption.exists { base =>
              nodeToString(base).contains(param.name) || aliases.sameReference(base, param)
            }
          case identifier: Identifier =>
            call.argument.headOption.exists { base =>
              nodeToString(base).contains(identifier.name) || aliases.sameReference(base, identifier)
            }
          case _ => false
        }
      case _ => false
    }
  }

  private def isAlias(use: CfgNode, inElement: CfgNode): Boolean = {
    use match {
      case useCall: Call =>
        inElement match {
          case inCall: Call =>
            val (useBase, useAccessPath) = toTrackedBaseAndAccessPathSimple(useCall)
            val (inBase, inAccessPath)   = toTrackedBaseAndAccessPathSimple(inCall)
            aliases.base(useBase, useCall) == aliases.base(inBase, inCall) &&
            useAccessPath.matchAndDiff(inAccessPath.elements)._1 == MatchResult.EXACT_MATCH
          case _ => false
        }
      case _ => false
    }
  }

  def uses(node: CfgNode): Set[CfgNode] = {
    val n: Set[CfgNode] = node match {
      case ret: Return                  => ret.astChildren.collect { case x: Expression => x }.toSet
      case call: Call                   => call.argument.toSet
      case paramOut: MethodParameterOut => Set(paramOut)
      case thrown: ControlStructure if dart && thrown.controlStructureType == ControlStructureTypes.THROW =>
        thrown._argumentOut.cast[Expression].toSet
      case _ => Set()
    }
    n.filterNot(_.isInstanceOf[FieldIdentifier])
  }

  /** Compares arguments of calls with incoming definitions to see if they refer to the same variable
    */
  private def sameVariable(use: CfgNode, inElement: CfgNode): Boolean = {
    inElement match {
      case param: MethodParameterIn =>
        nodeToString(use).contains(param.name)
      case call: Call if indirectionAccessSet.contains(call.name) =>
        call.argumentOption(1).exists(x => nodeToString(use).contains(x.code))
      // Captured receivers can give different storage locations the same source text.
      case call: Call if dart && containerSet.contains(call.name) && (use match {
            case other: Call => containerSet.contains(other.name)
            case _           => false
          }) =>
        false
      case call: Call =>
        nodeToString(use).contains(call.code)
      case identifier: Identifier => nodeToString(use).contains(identifier.name)
      case _                      => false
    }
  }

  private def nodeToString(node: CfgNode): Option[String] = {
    node match {
      case ident: Identifier     => Some(ident.name)
      case exp: Expression       => Some(exp.code)
      case p: MethodParameterIn  => Some(p.name)
      case p: MethodParameterOut => Some(p.name)
      case _                     => None
    }
  }

}
