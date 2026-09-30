package io.joern.dartsrc2cpg

import io.joern.x2cpg.{Ast, ValidationMode}
import io.shiftleft.codepropertygraph.generated.{Cpg, DispatchTypes, EvaluationStrategies, Operators}
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.passes.CpgPass
import org.slf4j.LoggerFactory
import scala.collection.mutable
import ujson.Value

class AstCreationPass(cpg: Cpg, units: Seq[Value], config: Config) extends CpgPass(cpg) {
  private implicit val schemaValidation: ValidationMode = config.schemaValidation
  private val logger                                    = LoggerFactory.getLogger(getClass)
  private val symbols = units.flatMap(_("symbols").arr).map(symbol => symbol("id").str -> symbol).toMap
  private def string(node: Value, key: String, default: String = ""): String =
    node.obj.get(key).flatMap(_.strOpt).getOrElse(default)
  private def symbol(node: Value, key: String): Value = symbols.getOrElse(string(node, key), ujson.Obj())

  override def run(diffGraph: DiffGraphBuilder): Unit = units.foreach { unit =>
    val filename                                        = java.net.URI.create(unit("file").str).getPath
    val source                                          = unit("source").str
    val nodes                                           = unit("nodes").arr
    val declarations                                    = mutable.Map.empty[String, NewNode]
    def children(node: Value, role: String): Seq[Value] =
      node("children").arr.filter(_("role").str == role).map(entry => nodes(entry("node").num.toInt)).toSeq
    def child(node: Value, role: String): Value = children(node, role).head
    def code(node: Value): String               =
      source.substring(node("offset").num.toInt, (node("offset").num + node("length").num).toInt)
    def tpe(node: Value): String                         = string(node, "type", "ANY")
    def located[T <: AstNodeNew](out: T, node: Value): T = {
      out.lineNumber = Some(node("line").num.toInt)
      // Both the analyzer and JVM strings use UTF-16 code units; CPG columns are one-based.
      out.columnNumber = Some(node("column").num.toInt)
      out
    }
    def args(call: NewNode, values: Seq[Ast], indexes: Seq[Int] = Nil): Ast = {
      values.zipWithIndex.foldLeft(Ast(call)) { case (result, (value, index)) =>
        value.root.foreach {
          case expr: ExpressionNew =>
            expr.order = index + 1
            expr.argumentIndex = indexes.lift(index).getOrElse(index + 1)
          case _ =>
        }
        result.withChild(value).withArgEdges(call, value.root.toList)
      }
    }
    def operator(node: Value, name: String, values: Seq[Ast]): Ast =
      args(
        located(
          NewCall()
            .name(name)
            .methodFullName(name)
            .code(code(node))
            .typeFullName(tpe(node))
            .dispatchType(DispatchTypes.STATIC_DISPATCH),
          node
        ),
        values
      )
    def identifier(node: Value, name: String, id: String, typ: String): Ast = {
      val out = located(NewIdentifier().name(name).code(name).typeFullName(typ), node)
      declarations.get(id).fold(Ast(out))(target => Ast(out).withRefEdge(out, target))
    }
    def expression(node: Value): Ast = string(node, "kind") match {
      case "SimpleIdentifier" => identifier(node, string(node, "name"), string(node, "reference"), tpe(node))
      case "StringLiteral" | "IntegerLiteral" | "DoubleLiteral" | "BooleanLiteral" | "NullLiteral" =>
        Ast(located(NewLiteral().code(code(node)).typeFullName(tpe(node)), node))
      case "NamedExpression" =>
        val ast = expression(child(node, "expression"))
        ast.root.collect { case expressionRoot: ExpressionNew =>
          expressionRoot.argumentName = Some(string(node, "name"))
        }
        ast
      case "MethodInvocation" =>
        val target = symbol(node, "target")
        val name   = string(child(node, "name"), "name")
        val call   = located(
          NewCall()
            .name(name)
            .methodFullName(string(node, "target", s"<unresolved>.$name"))
            .code(code(node))
            .typeFullName(tpe(node))
            .dispatchType(DispatchTypes.STATIC_DISPATCH),
          node
        )
        val arguments  = child(node, "arguments")
        val parameters = target.obj.get("parameters").map(_.arr.map(_.str).toSeq).getOrElse(Seq.empty)
        val bindings   = arguments("bindings").arr.zipWithIndex.map { case (binding, i) =>
          val index = parameters.indexOf(string(binding, "parameter"))
          if (index >= 0) index + 1 else i + 1
        }.toSeq
        args(call, children(arguments, "argument").map(expression), bindings)
      case "IndexExpression" =>
        operator(node, Operators.indexAccess, Seq(expression(child(node, "target")), expression(child(node, "index"))))
      case "AssignmentExpression" if string(node, "operator") == "=" =>
        operator(node, Operators.assignment, Seq(expression(child(node, "left")), expression(child(node, "right"))))
      case _ =>
        logger.warn(s"Unsupported Dart expression in $filename: ${code(node)}")
        Ast(located(NewUnknown().code(code(node)).parserTypeName(string(node, "kind")), node))
    }
    def returned(node: Value, value: Value): Ast =
      args(located(NewReturn().code(code(node)), node), Seq(expression(value)))
    def statements(node: Value): Seq[Ast] = string(node, "kind") match {
      case "Block"                        => children(node, "statement").flatMap(statements)
      case "VariableDeclarationStatement" => statements(child(node, "variables"))
      case "VariableDeclarationList"      => children(node, "variable").flatMap(statements)
      case "VariableDeclaration"          =>
        val name  = string(node, "name")
        val typ   = string(symbol(node, "declaration"), "type", "ANY")
        val local = located(NewLocal().name(name).code(name).typeFullName(typ), node)
        declarations(string(node, "declaration")) = local
        Seq(Ast(local)) ++ children(node, "initializer").map { init =>
          operator(
            node,
            Operators.assignment,
            Seq(identifier(node, name, string(node, "declaration"), typ), expression(init))
          )
        }
      case "ExpressionStatement" => Seq(expression(child(node, "expression")))
      case "ReturnStatement"     =>
        Seq(args(located(NewReturn().code(code(node)), node), children(node, "expression").map(expression)))
      case _ => Seq(expression(node))
    }
    def method(node: Value): Ast = {
      declarations.clear()
      val sym        = symbol(node, "declaration")
      val fullName   = string(node, "declaration", s"$filename:${string(node, "name")}")
      val function   = child(node, "function")
      val parameters = children(child(function, "parameters"), "parameter").zipWithIndex.map { case (wrapped, index) =>
        val parameter =
          if (string(wrapped, "kind") == "DefaultFormalParameter") child(wrapped, "parameter") else wrapped
        val out = located(
          NewMethodParameterIn()
            .name(string(parameter, "name"))
            .code(code(parameter))
            .index(index + 1)
            .order(index + 1)
            .typeFullName(string(symbol(parameter, "declaration"), "type", "ANY"))
            .evaluationStrategy(EvaluationStrategies.BY_VALUE)
            .isVariadic(false),
          parameter
        )
        declarations(string(parameter, "declaration")) = out
        Ast(out)
      }
      val body     = child(function, "body")
      val bodyAsts = string(body, "kind") match {
        case "BlockFunctionBody"      => statements(child(body, "block"))
        case "ExpressionFunctionBody" => Seq(returned(body, child(body, "expression")))
        case _                        => Seq(expression(body))
      }
      val out = located(
        NewMethod()
          .name(string(node, "name"))
          .fullName(fullName)
          .code(code(node))
          .filename(filename)
          .isExternal(false)
          .signature(s"${string(sym, "returnType", "ANY")}(${parameters.size})")
          .astParentType("NAMESPACE_BLOCK")
          .astParentFullName(s"$filename:<global>"),
        node
      )
      Ast(out)
        .withChildren(parameters)
        .withChild(Ast(located(NewBlock().code(code(body)).typeFullName("ANY"), body)).withChildren(bodyAsts))
        .withChild(
          Ast(
            NewMethodReturn()
              .typeFullName(string(sym, "returnType", "ANY"))
              .evaluationStrategy(EvaluationStrategies.BY_VALUE)
              .code("RET")
          )
        )
    }
    unit("diagnostics").arr.foreach(diagnostic => logger.warn(s"$filename: ${diagnostic("message").str}"))
    val namespace = NewNamespaceBlock().name("<global>").fullName(s"$filename:<global>").filename(filename)
    val asts      = children(nodes.head, "declaration").map { node =>
      if (string(node, "kind") == "FunctionDeclaration") method(node) else expression(node)
    }
    val file = NewFile().name(filename)
    if (!config.disableFileContent) file.content(source)
    Ast.storeInDiffGraph(Ast(file).withChild(Ast(namespace).withChildren(asts)), diffGraph)
  }
}
