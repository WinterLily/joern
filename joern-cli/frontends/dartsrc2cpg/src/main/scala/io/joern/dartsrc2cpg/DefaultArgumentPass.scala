package io.joern.dartsrc2cpg

import io.joern.x2cpg.{Ast, ValidationMode}
import io.joern.x2cpg.frontendspecific.DartLanguage
import io.shiftleft.codepropertygraph.generated.{Cpg, DispatchTypes, EdgeTypes, EvaluationStrategies, ModifierTypes}
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.passes.CpgPass
import io.shiftleft.semanticcpg.language.*
import ujson.Value

object DefaultArgumentPass {
  val DefaultTag = "dart.defaultArgument"
}

class DefaultArgumentPass(cpg: Cpg, units: Seq[Value]) extends CpgPass(cpg) {
  private implicit val resolver: ICallResolver                               = NoResolve
  private implicit val validation: ValidationMode                            = ValidationMode.Enabled
  private def text(value: Value, key: String, fallback: String = ""): String =
    value.obj.get(key).flatMap(_.strOpt).getOrElse(fallback)
  private def flag(value: Value, key: String): Boolean      = value.obj.get(key).flatMap(_.boolOpt).getOrElse(false)
  private def values(value: Value, key: String): Seq[Value] = value.obj.get(key).map(_.arr.toSeq).getOrElse(Nil)
  private val symbols = units.flatMap(values(_, "symbols")).map(symbol => text(symbol, "id") -> symbol).toMap
  private def parameters(id: String): Seq[Value] =
    values(symbols.getOrElse(id, ujson.Obj()), "parameters").map(id => symbols.getOrElse(id.str, ujson.Obj()))
  private var adaptedCalls     = 0
  private var adapters         = 0
  private var externalAdapters = 0
  def report: Value            = ujson.Obj(
    "adaptedCalls"           -> adaptedCalls,
    "targetAdapters"         -> adapters,
    "externalTargetAdapters" -> externalAdapters
  )

  override def run(diffGraph: DiffGraphBuilder): Unit = {
    val methods = cpg.method.map(method => method.fullName -> method).toMap
    cpg.call.toList.zipWithIndex.foreach { case (call, ordinal) =>
      val bound      = methods.get(call.methodFullName).filter(_.name == "<bound>")
      val invocation =
        bound.flatMap(_.ast.isCall.filter(_.tag.nameExact(DartLanguage.ResolvedDispatchTag).nonEmpty).headOption)
      val targets = invocation.map(_.callee.toList).getOrElse {
        (call.callee.toList ++ methods
          .get(call.methodFullName)
          .filter(_ => call.dispatchType == DispatchTypes.STATIC_DISPATCH)).distinct
      }
      val sourceArguments = call.argument.toList
      // These literals describe declaration defaults, not values supplied by the caller.
      val supplied = sourceArguments.filter(_.tag.nameExact(DefaultArgumentPass.DefaultTag).isEmpty)
      def actual(parameter: Value, index: Int): Option[Expression] =
        if (flag(parameter, "named")) supplied.find(_.argumentName.contains(text(parameter, "name")))
        else supplied.find(argument => argument.argumentName.isEmpty && argument.argumentIndex == index)
      def defaultArgument(parameter: Value, index: Int): Option[Expression] =
        if (flag(parameter, "named")) sourceArguments.find(_.argumentName.contains(text(parameter, "name")))
        else sourceArguments.find(_.argumentIndex == index)
      val differs = targets.exists { target =>
        parameters(target.fullName).zipWithIndex.exists { case (parameter, index) =>
          !flag(parameter, "required") && actual(parameter, index + 1).isEmpty &&
          defaultArgument(parameter, index + 1).forall(_.code != text(parameter, "defaultValue", "null"))
        }
      }
      val captured = bound.flatMap(_.ast.isLocal.nameExact("this").headOption)
      for {
        owner     <- call.inAst.isMethod.headOption
        namespace <- owner.inAst.isNamespaceBlock.headOption
        if differs && targets.nonEmpty && (bound.isEmpty || captured.nonEmpty)
      } {
        val declaration = bound.orElse(methods.get(call.methodFullName))
        declaration.foreach { selected =>
          sourceArguments.filterNot(supplied.contains).foreach { argument =>
            argument.tag.nameExact(DefaultArgumentPass.DefaultTag).foreach(diffGraph.removeNode)
            diffGraph.removeNode(argument)
          }
          call.outE(EdgeTypes.CALL).foreach(diffGraph.removeEdge)
          val originalTarget = call.methodFullName
          targets.sortBy(_.fullName).foreach { target =>
            val id     = s"${owner.fullName}:<defaults:$ordinal:${target.fullName}>"
            val inputs = selected.parameter.toList.map { parameter =>
              NewMethodParameterIn()
                .name(parameter.name)
                .code(parameter.code)
                .index(parameter.index)
                .order(parameter.order)
                .typeFullName(parameter.typeFullName)
                .evaluationStrategy(parameter.evaluationStrategy)
                .isVariadic(parameter.isVariadic)
            }
            val locals = captured.toList.map { local =>
              NewLocal()
                .name(local.name)
                .code(local.code)
                .typeFullName(local.typeFullName)
                .closureBindingId(local.closureBindingId)
            }
            def reference(declaration: NewNode, name: String, typ: String): Ast = {
              val value = NewIdentifier().name(name).code(name).typeFullName(typ)
              Ast(value).withRefEdge(value, declaration)
            }
            val receiver = locals.headOption
              .map(local => reference(local, local.name, local.typeFullName))
              .orElse(
                inputs.find(_.index == 0).map(parameter => reference(parameter, parameter.name, parameter.typeFullName))
              )
            val arguments = parameters(target.fullName).zipWithIndex.map { case (parameter, index) =>
              val ast =
                actual(parameter, index + 1).flatMap(argument => inputs.find(_.index == argument.argumentIndex)) match {
                  case Some(input) => reference(input, input.name, input.typeFullName)
                  case None        =>
                    Ast(
                      NewLiteral()
                        .code(text(parameter, "defaultValue", "null"))
                        .typeFullName(text(parameter, "typeId", text(parameter, "type", "ANY")))
                    )
                }
              if (flag(parameter, "named")) ast.root.collect { case value: ExpressionNew =>
                value.argumentName(Some(text(parameter, "name")))
              }
              ast -> (index + 1)
            }
            val delegate = NewCall()
              .name(target.name)
              .methodFullName(target.fullName)
              .code(call.code)
              .dispatchType(DispatchTypes.STATIC_DISPATCH)
              .typeFullName(target.methodReturn.typeFullName)
            val operands = receiver.toList.map(_ -> 0) ++ arguments
            val bodyCall = operands.zipWithIndex.foldLeft(Ast(delegate)) { case (ast, ((argument, index), order)) =>
              argument.root.collect { case value: ExpressionNew => value.argumentIndex(index).order(order + 1) }
              ast.withChild(argument).withArgEdges(delegate, argument.root.toList)
            }
            val withReceiver = receiver.flatMap(_.root).fold(bodyCall)(bodyCall.withReceiverEdge(delegate, _))
            val result       =
              if (target.methodReturn.typeFullName == "void") withReceiver
              else {
                val returned = NewReturn().code(s"return ${call.code}")
                Ast(returned).withChild(withReceiver).withArgEdges(returned, withReceiver.root.toList)
              }
            val body = Ast(NewBlock().code("<default arguments>").typeFullName(target.methodReturn.typeFullName))
              .withChildren(locals.map(Ast(_)))
              .withChild(result)
            val method = NewMethod()
              .name("<defaultArguments>")
              .fullName(id)
              .code(call.code)
              .filename(owner.filename)
              .isExternal(false)
              .signature(selected.signature)
              .genericSignature(selected.genericSignature)
              .astParentType("NAMESPACE_BLOCK")
              .astParentFullName(namespace.fullName)
            val ast = Ast(method)
              .withChildren(inputs.map(Ast(_)))
              .withChild(body)
              .withChild(
                Ast(
                  NewMethodReturn()
                    .code("RET")
                    .typeFullName(target.methodReturn.typeFullName)
                    .evaluationStrategy(EvaluationStrategies.BY_VALUE)
                )
              )
              .withChild(Ast(NewModifier().modifierType(ModifierTypes.PRIVATE)))
              .withChild(
                Ast(NewAnnotation().name("defaultArguments").fullName("dart.defaultArguments").code(originalTarget))
              )
            Ast.storeInDiffGraph(ast, diffGraph)
            diffGraph.addEdge(namespace, method, EdgeTypes.AST)
            diffGraph.addEdge(call, method, EdgeTypes.CALL)
            diffGraph.addEdge(delegate, target, EdgeTypes.CALL)
            adapters += 1
            if (target.isExternal) externalAdapters += 1
          }
          val tag = NewTag().name(DartLanguage.ResolvedDispatchTag).value("defaultArguments")
          diffGraph.addNode(tag)
          diffGraph.addEdge(call, tag, EdgeTypes.TAGGED_BY)
          adaptedCalls += 1
        }
      }
    }
  }
}
