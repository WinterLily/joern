package io.joern.dartsrc2cpg

import io.shiftleft.codepropertygraph.generated.{Cpg, DispatchTypes, EdgeTypes}
import io.shiftleft.passes.CpgPass
import io.shiftleft.semanticcpg.language.*
import ujson.Value

class MixinSuperCallPass(cpg: Cpg, units: Seq[Value]) extends CpgPass(cpg) {
  private def text(value: Value, key: String): String       = value.obj.get(key).flatMap(_.strOpt).getOrElse("")
  private def values(value: Value, key: String): Seq[Value] = value.obj.get(key).map(_.arr.toSeq).getOrElse(Nil)

  override def run(diffGraph: DiffGraphBuilder): Unit = {
    val symbols = units.flatMap(values(_, "symbols")).map(symbol => text(symbol, "id") -> symbol).toMap
    val targets = symbols.values.toSeq
      .flatMap(values(_, "mixinSuperTargets"))
      .groupMap(target => (text(target, "mixin"), text(target, "name"), text(target, "kind")))(text(_, "target"))
    val sites = units.flatMap { unit =>
      val file  = java.net.URI.create(text(unit, "file")).getPath
      val nodes = values(unit, "nodes")
      nodes.filter(node => text(node, "mixinSuper").nonEmpty).flatMap { node =>
        val update = Set("AssignmentExpression", "PrefixExpression", "PostfixExpression").contains(text(node, "kind"))
        val keys   = if (update) Seq("read", "write") else Seq("target", "reference", "operatorTarget")
        val locations = Seq(node) ++ (if (update)
                                        values(node, "children")
                                          .filter(child => Set("left", "operand").contains(text(child, "role")))
                                          .map(child => nodes(child("node").num.toInt))
                                      else Nil)
        for {
          location <- locations
          id       <- keys.map(text(node, _)).filter(_.nonEmpty)
        } yield (file, location("line").num.toInt, location("column").num.toInt, id) -> text(node, "mixinSuper")
      }
    }.toMap
    if (sites.isEmpty || targets.isEmpty) return
    val methods = cpg.method.map(method => method.fullName -> method).toMap
    cpg.call.dispatchTypeExact(DispatchTypes.STATIC_DISPATCH).foreach { call =>
      for {
        line   <- call.lineNumber
        column <- call.columnNumber
        owner  <- call.inAst.isMethod.headOption
        mixin  <- sites.get((owner.filename, line, column, call.methodFullName))
        symbol <- symbols.get(call.methodFullName)
        id     <- targets
          .getOrElse((mixin, text(symbol, "name"), text(symbol, "kind")), Nil)
          .distinct
          .filterNot(_ == call.methodFullName)
        method <- methods.get(id)
      } diffGraph.addEdge(call, method, EdgeTypes.CALL)
    }
  }
}
