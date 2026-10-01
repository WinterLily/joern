package io.joern.dataflowengineoss.queryengine

import io.joern.dataflowengineoss.queryengine.QueryEngineStatistic.{PATH_CACHE_HITS, PATH_CACHE_MISSES}
import io.joern.dataflowengineoss.passes.reachingdef.ReferenceAliases
import io.joern.dataflowengineoss.semanticsloader.Semantics
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.language.*

import java.util.concurrent.Callable
import scala.collection.mutable

/** Callable for solving a ReachableByTask
  *
  * A Java Callable is "a task that returns a result and may throw an exception", and this is the callable for
  * calculating the result for `task`.
  *
  * @param task
  *   the data flow problem to solve
  * @param context
  *   state of the data flow engine
  * @param sources
  *   the set of sources that we are looking to reach.
  */
class TaskSolver(task: ReachableByTask, context: EngineContext, sources: Set[CfgNode]) extends Callable[TaskSummary] {

  import Engine._
  private lazy val dart             = io.joern.dataflowengineoss.isDart(task.sink)
  private lazy val referenceAliases = ReferenceAliases.forNode(task.sink)

  /** Entry point of callable. First checks if the maximum call depth has been exceeded, in which case an empty result
    * list is returned. Otherwise, the task is solved and its results are returned.
    */
  override def call(): TaskSummary = {
    implicit val sem: Semantics = context.semantics
    val path                    = Vector(
      PathElement(
        task.sink,
        task.callSiteStack,
        outEdgeLabel = task.fingerprint.outputChannel.edgeLabel,
        fieldDemand = task.fingerprint.fieldDemand,
        storageDemands = task.fingerprint.storageDemands
      )
    )
    val table: mutable.Map[TaskFingerprint, Vector[ReachableByResult]] = mutable.Map()
    results(task.sink, path, table, task.callSiteStack)
    // TODO why do we update the call depth here?
    val finalResults = table.get(task.fingerprint).get.map { r =>
      r.copy(
        taskStack = r.taskStack.dropRight(1) :+ r.fingerprint.copy(callDepth = task.callDepth),
        path = r.path ++ task.initialPath
      )
    }
    val (partial, complete) = finalResults.partition(_.partial)
    val newTasks            = new TaskCreator(context).createFromResults(partial)
    TaskSummary(complete.flatMap(r => resultToTableEntries(r)), newTasks)
  }

  private def resultToTableEntries(r: ReachableByResult): List[(TaskFingerprint, TableEntry)] = {
    r.taskStack.indices.map { i =>
      val parentTask = r.taskStack(i)
      val pathToSink =
        if (!dart) r.path.slice(0, r.path.map(_.node).indexOf(parentTask.sink))
        else
          r.path.takeWhile(element =>
            element.node != parentTask.sink ||
              element.callSiteStack != parentTask.callSiteStack || element.outputChannel != parentTask.outputChannel ||
              element.fieldDemand != parentTask.fieldDemand || element.storageDemands != parentTask.storageDemands
          )
      val newPath = pathToSink :+ PathElement(
        parentTask.sink,
        parentTask.callSiteStack,
        outEdgeLabel = parentTask.outputChannel.edgeLabel,
        fieldDemand = parentTask.fieldDemand,
        storageDemands = parentTask.storageDemands
      )
      (parentTask, TableEntry(path = newPath))
    }.toList
  }

  /** Recursively expand the DDG backwards and return a list of all results, given by at least a source node in
    * `sourceSymbols` and the path between the source symbol and the sink.
    *
    * This method stays within the method (intra-procedural analysis) and terminates at method parameters and at output
    * arguments.
    *
    * @param path
    *   This is a path from a node to the sink. The first node of the path is expanded by this method
    *
    * @param table
    *   The result table is a cache of known results that we can re-use
    *
    * @param callSiteStack
    *   This stack holds all call sites we expanded to arrive at the generation of the current task
    */
  private def results[NodeType <: CfgNode](
    sink: CfgNode,
    path: Vector[PathElement],
    table: mutable.Map[TaskFingerprint, Vector[ReachableByResult]],
    callSiteStack: List[Call]
  )(implicit semantics: Semantics): Vector[ReachableByResult] = {

    val curNode = path.head.node

    /** For each parent of the current node, determined via `expandIn`, check if results are available in the result
      * table. If not, determine results recursively.
      */
    def computeResultsForParents() = {
      deduplicateWithinTask(
        expandIn(
          curNode.asInstanceOf[CfgNode],
          path,
          callSiteStack,
          context.config,
          Some(referenceAliases)
        ).iterator.flatMap { parent =>
          createResultsFromCacheOrCompute(parent, path)
        }.toVector
      )
    }

    def deduplicateWithinTask(vec: Vector[ReachableByResult]): Vector[ReachableByResult] = {
      vec
        .groupBy { result =>
          val head = result.path.headOption
            .map(x => (x.node, x.callSiteStack, x.isOutputArg, x.outputChannel, x.fieldDemand, x.storageDemands))
            .get
          val last = result.path.lastOption
            .map(x => (x.node, x.callSiteStack, x.isOutputArg, x.outputChannel, x.fieldDemand, x.storageDemands))
            .get
          (head, last, result.partial, result.callDepth)
        }
        .flatMap { case (_, list) =>
          WitnessSelection.select[ReachableByResult](
            list.toList,
            _.path,
            context.config,
            x =>
              x.callDepth.toString + " " +
                x.taskStack
                  .map(x => x.sink.id.toString + ":" + x.callSiteStack.map(_.id).mkString("|"))
                  .toString + " " + x.path
                  .map(_.orderingKey)
                  .mkString("-")
          )
        }
        .toVector
    }

    def createResultsFromCacheOrCompute(elemToPrepend: PathElement, path: Vector[PathElement]) = {
      val cachedResult = createFromTable(table, elemToPrepend, task.callSiteStack, path, task.callDepth)
      if (cachedResult.isDefined) {
        QueryEngineStatistics.incrementBy(PATH_CACHE_HITS, 1L)
        cachedResult.get
      } else {
        QueryEngineStatistics.incrementBy(PATH_CACHE_MISSES, 1L)
        val newPath = elemToPrepend +: path
        results(sink, newPath, table, callSiteStack)
      }
    }

    /** For a given path, determine whether results for the first element (`first`) are stored in the table, and if so,
      * for each result, determine the path up to `first` and prepend it to `path`, giving us new results via table
      * lookup.
      */
    def createFromTable(
      table: mutable.Map[TaskFingerprint, Vector[ReachableByResult]],
      first: PathElement,
      callSiteStack: List[Call],
      remainder: Vector[PathElement],
      callDepth: Int
    ): Option[Vector[ReachableByResult]] = {
      table
        .get(
          TaskFingerprint(
            first.node.asInstanceOf[CfgNode],
            callSiteStack,
            callDepth,
            first.outputChannel,
            first.fieldDemand,
            first.storageDemands
          )
        )
        .map { res =>
          res.map { r =>
            val stopIndex = r.path
              .map(x => (x.node, x.callSiteStack, x.outputChannel, x.fieldDemand, x.storageDemands))
              .indexOf((first.node, first.callSiteStack, first.outputChannel, first.fieldDemand, first.storageDemands))
            val pathToFirstNode = r.path.slice(0, stopIndex)
            val completePath    = pathToFirstNode ++ (first +: remainder)
            r.copy(path = Vector(completePath.head) ++ completePath.tail)
          }
        }
    }

    def createPartialResultForOutputArgOrRet() = {
      Vector(
        ReachableByResult(
          task.taskStack,
          (if (dart) path.head.copy(callSiteStack = callSiteStack, isOutputArg = true)
           else PathElement(path.head.node, callSiteStack, isOutputArg = true)) +: path.tail,
          partial = true
        )
      )
    }

    /** Determine results for the current node
      */
    val res = curNode match {
      case _: Call if path.head.outputChannel != OutputChannel.Normal =>
        createPartialResultForOutputArgOrRet()
      case call: Call if StaticStorage.readKey(call).nonEmpty =>
        (if (sources.contains(call)) Vector(ReachableByResult(task.taskStack, path)) else Vector.empty) ++
          createPartialResultForOutputArgOrRet()
      // Case 1: we have reached a source => return result and continue traversing (expand into parents)
      case x if sources.contains(x.asInstanceOf[NodeType]) =>
        if (x.isInstanceOf[MethodParameterIn]) {
          Vector(
            ReachableByResult(task.taskStack, path),
            ReachableByResult(task.taskStack, path, partial = true)
          ) ++ computeResultsForParents()
        } else {
          Vector(ReachableByResult(task.taskStack, path)) ++ computeResultsForParents()
        }
      // Case 2: we have reached a method parameter (that isn't a source) => return partial result and stop traversing
      case _: MethodParameterIn =>
        Vector(ReachableByResult(task.taskStack, path, partial = true))
      // Case 3: we have reached a call to an internal method without semantic (return value) and
      // this isn't the start node => return partial result and stop traversing
      case call: Call
          if isCallToInternalMethodWithoutSemantic(call)
            && !isArgOrRetOfMethodWeCameFrom(call, path) =>
        createPartialResultForOutputArgOrRet()

      // Case 4: we have reached an argument to an internal method without semantic (output argument) and
      // this isn't the start node nor is it the argument for the parameter we just expanded => return partial result and stop traversing
      case arg: Expression
          if path.size > 1
            && arg.inCall.toList.exists(c => isCallToInternalMethodWithoutSemantic(c))
            && !arg.inCall.headOption.exists(x => isArgOrRetOfMethodWeCameFrom(x, path)) =>
        createPartialResultForOutputArgOrRet()

      case _: MethodRef => createPartialResultForOutputArgOrRet()

      // All other cases: expand into parents
      case _ =>
        computeResultsForParents()
    }
    val key =
      TaskFingerprint(
        curNode.asInstanceOf[CfgNode],
        task.callSiteStack,
        task.callDepth,
        path.head.outputChannel,
        path.head.fieldDemand,
        path.head.storageDemands
      )
    table.updateWith(key) {
      case Some(existingValue) => Some(existingValue ++ res)
      case None                => Some(res)
    }
    res
  }

  private def isArgOrRetOfMethodWeCameFrom(call: Call, path: Vector[PathElement]): Boolean =
    path match {
      case Vector(_, PathElement(x: MethodReturn, _, _, _, _, _, _), _*)      => methodsForCall(call).contains(x.method)
      case Vector(_, PathElement(x: MethodParameterIn, _, _, _, _, _, _), _*) => methodsForCall(call).contains(x.method)
      case _                                                                  => false
    }

}
