package io.joern.dartsrc2cpg

import io.joern.x2cpg.passes.base.MethodStubCreator
import io.shiftleft.codepropertygraph.generated.{Cpg, DispatchTypes}
import io.shiftleft.passes.CpgPass
import io.shiftleft.semanticcpg.language.*
import ujson.Value

// The shared stub pass visits CALLs; an external tear-off may have no CALL site.
class MethodReferencePass(cpg: Cpg, units: Seq[Value]) extends CpgPass(cpg) {
  override def run(diffGraph: DiffGraphBuilder): Unit = {
    val symbols = units.flatMap(_("symbols").arr).map(s => s("id").str -> s).toMap
    val defined = cpg.method.fullName.toSet
    cpg.methodRef.methodFullName.toSet.diff(defined).toSeq.sorted.foreach { id =>
      val symbol                              = symbols.getOrElse(id, ujson.Obj())
      def text(key: String, fallback: String) = symbol.obj.get(key).flatMap(_.strOpt).getOrElse(fallback)
      def flag(key: String)                   = symbol.obj.get(key).flatMap(_.boolOpt).getOrElse(false)
      val instance                            = text("kind", "") match {
        case "CONSTRUCTOR" => !flag("factory")
        case "METHOD"      => !flag("static")
        case _             => false
      }
      val parameters = symbol.obj.get("parameters").map(_.arr.size).getOrElse(0)
      MethodStubCreator.createMethodStub(
        text("name", id.split(':').last),
        id,
        s"${text("returnType", "ANY")}($parameters)",
        DispatchTypes.STATIC_DISPATCH,
        parameters + (if (instance) 1 else 0),
        diffGraph,
        startWithInst = Some(instance)
      )
    }
  }
}
