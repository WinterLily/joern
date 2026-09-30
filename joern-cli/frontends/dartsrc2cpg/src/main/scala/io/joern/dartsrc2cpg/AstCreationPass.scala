package io.joern.dartsrc2cpg

import io.joern.x2cpg.{Ast, ValidationMode}
import io.shiftleft.codepropertygraph.generated.{
  Cpg,
  DispatchTypes,
  EvaluationStrategies,
  Operators,
  ControlStructureTypes,
  ModifierTypes
}
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.passes.CpgPass
import org.slf4j.LoggerFactory
import scala.collection.mutable
import ujson.Value

class AstCreationPass(cpg: Cpg, units: Seq[Value], config: Config) extends CpgPass(cpg) {
  private implicit val schemaValidation: ValidationMode = config.schemaValidation
  private val logger                                    = LoggerFactory.getLogger(getClass)
  private val symbols                                   =
    units.flatMap(_("symbols").arr).map(symbolRecord => symbolRecord("id").str -> symbolRecord).toMap
  private def string(syntax: Value, key: String, default: String = ""): String =
    syntax.obj.get(key).flatMap(_.strOpt).getOrElse(default)
  private def bool(syntax: Value, key: String): Boolean = syntax.obj.get(key).flatMap(_.boolOpt).getOrElse(false)
  private def sym(id: String): Value                    = symbols.getOrElse(id, ujson.Obj())
  private def symbol(syntax: Value, key: String = "declaration"): Value = sym(string(syntax, key))
  private def strings(syntax: Value, key: String): Seq[String]          =
    syntax.obj.get(key).map(_.arr.map(_.str).toSeq).getOrElse(Nil)

  override def run(diffGraph: DiffGraphBuilder): Unit = units.foreach { unit =>
    val filename                           = java.net.URI.create(unit("file").str).getPath
    val library                            = string(unit, "library", filename)
    val source                             = unit("source").str
    val nodes                              = unit("nodes").arr
    val declarations                       = mutable.Map.empty[String, NewNode]
    val extraMethods                       = mutable.ArrayBuffer.empty[Ast]
    var owner                              = s"$library:<global>"
    var ownerType                          = "NAMESPACE_BLOCK"
    var currentType                        = "ANY"
    var instanceFields                     = Seq.empty[Value]
    val functionValues                     = mutable.Map.empty[String, String]
    val boundTargets                       = mutable.Map.empty[String, String]
    var cascadeReceiver: Option[() => Ast] = None
    var temporary                          = 0
    val receiverOverrides                  = mutable.Map.empty[Int, () => Ast]
    val guardedAccesses                    = mutable.Set.empty[Int]

    def children(syntax: Value, role: String): Seq[Value] =
      syntax("children").arr.filter(_("role").str == role).map(entryNode => nodes(entryNode("node").num.toInt)).toSeq
    def child(syntax: Value, role: String): Value = children(syntax, role).head
    def code(syntax: Value): String               =
      source.substring(syntax("offset").num.toInt, (syntax("offset").num + syntax("length").num).toInt)
    def tpe(syntax: Value): String                         = string(syntax, "typeId", string(syntax, "type", "ANY"))
    def located[T <: AstNodeNew](out: T, syntax: Value): T = {
      out.lineNumber = Some(syntax("line").num.toInt)
      out.columnNumber = Some(syntax("column").num.toInt)
      out
    }
    def block(syntax: Value, values: Seq[Ast]): Ast =
      Ast(located(NewBlock().code(code(syntax)).typeFullName(tpe(syntax)), syntax)).withChildren(values)
    def args(call: NewNode, values: Seq[Ast], indexes: Seq[Int] = Nil): Ast =
      values.zipWithIndex.foldLeft(Ast(call)) { case (result, (value, index)) =>
        value.root.foreach {
          case expr: ExpressionNew =>
            expr.order = index + 1
            expr.argumentIndex = indexes.lift(index).getOrElse(index + 1)
          case _ =>
        }
        result.withChild(value).withArgEdges(call, value.root.toList)
      }
    def operator(syntax: Value, name: String, values: Seq[Ast]): Ast = args(
      located(
        NewCall()
          .name(name)
          .methodFullName(name)
          .code(code(syntax))
          .typeFullName(tpe(syntax))
          .dispatchType(DispatchTypes.STATIC_DISPATCH),
        syntax
      ),
      values
    )
    def literal(syntax: Value, value: String, typ: String = "ANY"): Ast =
      Ast(located(NewLiteral().code(value).typeFullName(typ), syntax))
    def identifier(syntax: Value, name: String, id: String, typ: String): Ast = {
      val out = located(NewIdentifier().name(name).code(name).typeFullName(typ), syntax)
      declarations.get(id).fold(Ast(out))(target => Ast(out).withRefEdge(out, target))
    }
    def thisAst(syntax: Value): Ast                            = identifier(syntax, "this", "this", currentType)
    def field(syntax: Value, receiver: Ast, name: String): Ast =
      operator(
        syntax,
        Operators.fieldAccess,
        Seq(receiver, Ast(located(NewFieldIdentifier().canonicalName(name).code(name), syntax)))
      )
    def saved(syntax: Value, value: Ast)(body: (() => Ast) => Ast): Ast = {
      temporary += 1
      val name = s"<tmp>$temporary"
      val typ  = value.root match {
        case Some(valueSyntax: NewCall)       => valueSyntax.typeFullName
        case Some(valueSyntax: NewIdentifier) => valueSyntax.typeFullName
        case Some(valueSyntax: NewLiteral)    => valueSyntax.typeFullName
        case Some(valueSyntax: NewBlock)      => valueSyntax.typeFullName
        case _                                => tpe(syntax)
      }
      val local = located(NewLocal().name(name).code(name).typeFullName(typ), syntax)
      declarations(name) = local
      def ref(): Ast = identifier(syntax, name, name, typ)
      block(syntax, Seq(Ast(local), operator(syntax, Operators.assignment, Seq(ref(), value)), body(() => ref())))
    }
    def nonNull(syntax: Value, value: Ast): Ast =
      operator(syntax, Operators.notEquals, Seq(value, literal(syntax, "null", "Null")))
    def guarded(syntax: Value, value: Ast)(body: (() => Ast) => Ast): Ast =
      saved(syntax, value)(ref =>
        operator(syntax, Operators.conditional, Seq(nonNull(syntax, ref()), body(ref), literal(syntax, "null", "Null")))
      )

    def call(
      syntax: Value,
      targetId: String,
      name: String,
      argumentList: Option[Value],
      receiver: Option[Ast] = None
    ): Ast = {
      val target   = sym(boundTargets.getOrElse(targetId, targetId))
      val params   = strings(target, "parameters")
      val actual   = argumentList.toSeq.flatMap(arguments => children(arguments, "argument"))
      val bindings = argumentList.toSeq.flatMap(_("bindings").arr).zipWithIndex.map { case (bodySyntax, i) =>
        val exportedIndex = params.indexOf(string(bodySyntax, "parameter"))
        val index         =
          if (exportedIndex >= 0) exportedIndex
          else
            actual
              .lift(i)
              .filter(arg => string(arg, "kind") == "NamedExpression")
              .map(arg => params.indexWhere(id => string(sym(id), "name") == string(arg, "name")))
              .getOrElse(-1)
        if (index >= 0) index + 1 else i + 1
      }
      val explicit = actual.map(expression)
      val defaults = params.zipWithIndex.collect {
        case (id, i) if !bindings.contains(i + 1) && !bool(sym(id), "required") =>
          val paramNode = sym(id)
          val ast       = literal(syntax, string(paramNode, "defaultValue", "null"), tpe(paramNode))
          if (bool(paramNode, "named")) ast.root.collect { case entryNode: ExpressionNew =>
            entryNode.argumentName = Some(string(paramNode, "name"))
          }
          (ast, i + 1)
      }
      val out = located(
        NewCall()
          .name(name)
          .methodFullName(if (targetId.nonEmpty) targetId else s"<unresolved>.$name")
          .code(code(syntax))
          .typeFullName(tpe(syntax))
          .dispatchType(if (receiver.nonEmpty) DispatchTypes.DYNAMIC_DISPATCH else DispatchTypes.STATIC_DISPATCH),
        syntax
      )
      val ast = args(
        out,
        receiver.toSeq ++ explicit ++ defaults.map(_._1),
        receiver.toSeq.map(_ => 0) ++ bindings ++ defaults.map(_._2)
      )
      receiver.flatMap(_.root).fold(ast)(receiverRoot => ast.withReceiverEdge(out, receiverRoot))
    }
    def methodRef(syntax: Value, id: String): Ast = {
      val ref = located(NewMethodRef().code(code(syntax)).methodFullName(id).typeFullName(tpe(syntax)), syntax)
      Ast(ref)
    }
    def boundMethodRef(syntax: Value, targetId: String, receiver: Ast): Ast = saved(syntax, receiver) { outerRef =>
      val id     = s"$filename#${syntax("offset").num.toInt}:bound"
      val target = sym(targetId)
      boundTargets(id) = targetId
      val outer          = outerRef()
      val capturedTarget = outer.refEdges.head.dst
      val bindingId      = s"$id:this"
      val local          = NewLocal()
        .name("this")
        .code("this")
        .typeFullName(outer.root.get.asInstanceOf[NewIdentifier].typeFullName)
        .closureBindingId(bindingId)
      val binding =
        NewClosureBinding().closureBindingId(bindingId).evaluationStrategy(EvaluationStrategies.BY_REFERENCE)
      val ref        = located(NewMethodRef().code(code(syntax)).methodFullName(id).typeFullName(tpe(syntax)), syntax)
      val parameters = strings(target, "parameters").zipWithIndex.map { case (paramNode, index) =>
        val symbol = sym(paramNode)
        NewMethodParameterIn()
          .name(string(symbol, "name"))
          .code(string(symbol, "name"))
          .index(index + 1)
          .order(index + 1)
          .typeFullName(tpe(symbol))
          .evaluationStrategy(EvaluationStrategies.BY_VALUE)
          .isVariadic(false)
      }
      val base    = NewIdentifier().name("this").code("this").typeFullName(local.typeFullName)
      val baseAst = Ast(base).withRefEdge(base, local)
      val values  = parameters.map { paramNode =>
        val value = NewIdentifier().name(paramNode.name).code(paramNode.name).typeFullName(paramNode.typeFullName)
        Ast(value).withRefEdge(value, paramNode)
      }
      val callNode = located(
        NewCall()
          .name(string(target, "name"))
          .methodFullName(targetId)
          .code(code(syntax))
          .typeFullName(string(target, "returnTypeId", "ANY"))
          .dispatchType(DispatchTypes.DYNAMIC_DISPATCH),
        syntax
      )
      val invocation = args(callNode, baseAst +: values, 0 to parameters.size).withReceiverEdge(callNode, base)
      val method     = NewMethod()
        .name("<bound>")
        .fullName(id)
        .code(code(syntax))
        .filename(filename)
        .isExternal(false)
        .signature(s"${string(target, "returnType", "ANY")}(${parameters.size})")
        .astParentType("NAMESPACE_BLOCK")
        .astParentFullName(s"$library:<global>")
      extraMethods += Ast(method)
        .withChildren(parameters.map(Ast(_)))
        .withChild(block(syntax, Seq(Ast(local), args(NewReturn().code(code(syntax)), Seq(invocation)))))
        .withChild(
          Ast(
            NewMethodReturn()
              .typeFullName(string(target, "returnTypeId", "ANY"))
              .code("RET")
              .evaluationStrategy(EvaluationStrategies.BY_VALUE)
          )
        )
        .withChild(Ast(NewModifier().modifierType(ModifierTypes.STATIC)))
      Ast(ref).merge(Ast(binding)).withCaptureEdge(ref, binding).withRefEdge(binding, capturedTarget)
    }
    def reference(syntax: Value, id: String, name: String, receiver: Option[Ast]): Ast = {
      val target    = sym(id)
      def base: Ast = if (bool(target, "static")) {
        val fullName = string(target, "owner", s"${string(target, "library", library)}:<global>")
        identifier(syntax, fullName, "", fullName)
      } else receiver.getOrElse(thisAst(syntax))
      string(target, "kind") match {
        case "FUNCTION" => methodRef(syntax, id)
        case "METHOD"   =>
          if (bool(target, "static")) methodRef(syntax, id)
          else boundMethodRef(syntax, id, receiver.getOrElse(thisAst(syntax)))
        case "GETTER" | "SETTER" =>
          if (bool(target, "synthetic")) field(syntax, base, name)
          else call(syntax, id, name, None, if (bool(target, "static")) None else Some(base))
        case "FIELD" | "TOP_LEVEL_VARIABLE" => field(syntax, base, name)
        case _ if id.isEmpty && Set("PropertyAccess", "PrefixedIdentifier").contains(string(syntax, "kind")) =>
          field(syntax, base, name)
        case _ => identifier(syntax, name, id, tpe(syntax))
      }
    }
    def binaryOperators = Map(
      "+"   -> Operators.addition,
      "-"   -> Operators.subtraction,
      "*"   -> Operators.multiplication,
      "/"   -> Operators.division,
      "~/"  -> Operators.division,
      "%"   -> Operators.modulo,
      "=="  -> Operators.equals,
      "!="  -> Operators.notEquals,
      "<"   -> Operators.lessThan,
      "<="  -> Operators.lessEqualsThan,
      ">"   -> Operators.greaterThan,
      ">="  -> Operators.greaterEqualsThan,
      "&&"  -> Operators.logicalAnd,
      "||"  -> Operators.logicalOr,
      "&"   -> Operators.and,
      "|"   -> Operators.or,
      "^"   -> Operators.xor,
      "<<"  -> Operators.shiftLeft,
      ">>"  -> Operators.arithmeticShiftRight,
      ">>>" -> Operators.logicalShiftRight
    )
    def unknown(syntax: Value): Ast = {
      logger.warn(s"Unsupported Dart ${string(syntax, "syntax", string(syntax, "kind"))} in $filename: ${code(syntax)}")
      Ast(
        located(
          NewUnknown().code(code(syntax)).parserTypeName(string(syntax, "syntax", string(syntax, "kind"))),
          syntax
        )
      )
    }
    def nullAware(syntax: Value): Boolean =
      bool(syntax, "nullAware") && !guardedAccesses.contains(syntax("id").num.toInt)
    def chainReceiver(syntax: Value): Option[Value] = string(syntax, "kind") match {
      case "MethodInvocation" | "PropertyAccess" | "PrefixedIdentifier" | "FunctionExpressionInvocation" =>
        children(syntax, "receiver").headOption
      case "IndexExpression" => children(syntax, "target").headOption
      case _                 => None
    }
    def firstNullAware(syntax: Value): Option[Value] = chainReceiver(syntax).flatMap { receiver =>
      firstNullAware(receiver).orElse(if (nullAware(syntax)) Some(syntax) else None)
    }
    def expression(syntax: Value): Ast = receiverOverrides.get(syntax("id").num.toInt) match {
      case Some(ref) => ref()
      case None      =>
        firstNullAware(syntax) match {
          case Some(access) =>
            val receiver = chainReceiver(access).get
            guarded(syntax, expression(receiver)) { ref =>
              val receiverId = receiver("id").num.toInt
              val accessId   = access("id").num.toInt
              receiverOverrides(receiverId) = ref
              guardedAccesses += accessId
              val result = expressionBody(syntax)
              receiverOverrides.remove(receiverId)
              guardedAccesses -= accessId
              result
            }
          case None => expressionBody(syntax)
        }
    }
    def expressionBody(syntax: Value): Ast = string(syntax, "kind") match {
      case "SimpleIdentifier" => reference(syntax, string(syntax, "reference"), string(syntax, "name"), None)
      case "ThisExpression" | "SuperExpression"                                                    => thisAst(syntax)
      case "StringLiteral" | "IntegerLiteral" | "DoubleLiteral" | "BooleanLiteral" | "NullLiteral" =>
        literal(syntax, code(syntax), tpe(syntax))
      case "NamedExpression" =>
        val ast = expression(child(syntax, "expression"))
        ast.root.collect { case entryNode: ExpressionNew => entryNode.argumentName = Some(string(syntax, "name")) }
        ast
      case "ParenthesizedExpression" | "InterpolationExpression" | "FunctionReference" =>
        expression(child(syntax, "expression"))
      case "MethodInvocation" =>
        val originalTarget = string(syntax, "target")
        val targetId       = functionValues.getOrElse(originalTarget, originalTarget)
        val name           = string(child(syntax, "name"), "name")
        val target         = sym(targetId)
        val functionValue  = Set("LOCAL_VARIABLE", "PARAMETER").contains(string(sym(originalTarget), "kind"))
        val receiver       =
          (if (functionValue) Some(identifier(syntax, name, originalTarget, tpe(sym(originalTarget)))) else None)
            .orElse(children(syntax, "receiver").headOption.map(expression))
            .orElse(cascadeReceiver.map(_()))
            .orElse(if (string(target, "kind") == "METHOD" && !bool(target, "static")) Some(thisAst(syntax)) else None)
        if (nullAware(syntax) && receiver.nonEmpty)
          guarded(syntax, receiver.get)(ref =>
            call(syntax, targetId, name, Some(child(syntax, "arguments")), Some(ref()))
          )
        else
          call(
            syntax,
            if (functionValue && targetId == originalTarget) "" else targetId,
            name,
            Some(child(syntax, "arguments")),
            if (bool(target, "static") && !functionValue) None else receiver
          )
      case "InstanceCreationExpression" =>
        call(
          syntax,
          string(syntax, "target"),
          string(syntax, "name"),
          Some(child(syntax, "arguments")),
          if (bool(symbol(syntax, "target"), "factory")) None else Some(operator(syntax, Operators.alloc, Nil))
        )
      case "ConstructorInvocation" =>
        call(
          syntax,
          string(syntax, "target"),
          string(syntax, "name"),
          Some(child(syntax, "arguments")),
          Some(thisAst(syntax))
        )
      case "ConstructorReference" => methodRef(syntax, string(syntax, "target"))
      case "ConstructorName"      =>
        call(syntax, string(syntax, "target"), string(syntax, "name"), None, Some(thisAst(syntax)))
      case "FunctionExpressionInvocation" =>
        val receiver = child(syntax, "receiver")
        val value    = expression(receiver)
        val targetId = value.root
          .collect { case ref: NewMethodRef => ref.methodFullName }
          .orElse(functionValues.get(string(receiver, "reference")))
          .getOrElse("")
        val base = if (string(sym(targetId), "kind") == "CONSTRUCTOR") {
          if (bool(sym(targetId), "factory")) None else Some(operator(syntax, Operators.alloc, Nil))
        } else Some(value)
        call(syntax, targetId, string(receiver, "name", "<invoke>"), Some(child(syntax, "arguments")), base)
      case "PropertyAccess" | "PrefixedIdentifier" =>
        val receiver =
          children(syntax, "receiver").headOption
            .map(expression)
            .orElse(cascadeReceiver.map(_()))
            .getOrElse(thisAst(syntax))
        if (nullAware(syntax))
          guarded(syntax, receiver)(ref =>
            reference(syntax, string(syntax, "reference"), string(syntax, "name"), Some(ref()))
          )
        else reference(syntax, string(syntax, "reference"), string(syntax, "name"), Some(receiver))
      case "IndexExpression" =>
        val base =
          children(syntax, "target").headOption
            .map(expression)
            .orElse(cascadeReceiver.map(_()))
            .getOrElse(thisAst(syntax))
        if (nullAware(syntax))
          guarded(syntax, base)(ref =>
            operator(syntax, Operators.indexAccess, Seq(ref(), expression(child(syntax, "index"))))
          )
        else operator(syntax, Operators.indexAccess, Seq(base, expression(child(syntax, "index"))))
      case "AssignmentExpression" =>
        val op                                              = string(syntax, "operator")
        val left                                            = child(syntax, "left")
        val right                                           = child(syntax, "right")
        def assign(read: () => Ast, write: Ast => Ast): Ast = {
          if (op == "??=")
            saved(syntax, read())(ref =>
              operator(syntax, Operators.conditional, Seq(nonNull(syntax, ref()), ref(), write(expression(right))))
            )
          else if (op == "=") write(expression(right))
          else
            write(
              operator(
                syntax,
                binaryOperators.getOrElse(op.dropRight(1), "<operator>.unknown"),
                Seq(read(), expression(right))
              )
            )
        }
        def access(receiver: () => Ast): Ast = {
          val readId                 = string(syntax, "read", string(left, "reference"))
          val writeId                = string(syntax, "write", string(left, "reference"))
          def read(): Ast            = reference(left, readId, string(left, "name"), Some(receiver()))
          def write(value: Ast): Ast = {
            if (string(sym(writeId), "kind") == "SETTER" && !bool(sym(writeId), "synthetic")) {
              val out = located(
                NewCall()
                  .name(string(left, "name"))
                  .methodFullName(writeId)
                  .code(code(syntax))
                  .typeFullName(tpe(syntax))
                  .dispatchType(
                    if (bool(sym(writeId), "static")) DispatchTypes.STATIC_DISPATCH else DispatchTypes.DYNAMIC_DISPATCH
                  ),
                syntax
              )
              if (bool(sym(writeId), "static")) args(out, Seq(value))
              else {
                val base = receiver()
                args(out, Seq(base, value), Seq(0, 1)).withReceiverEdge(out, base.root.get)
              }
            } else
              operator(
                syntax,
                Operators.assignment,
                Seq(reference(left, writeId, string(left, "name"), Some(receiver())), value)
              )
          }
          assign(() => read(), write)
        }
        string(left, "kind") match {
          case "IndexExpression" =>
            val target = children(left, "target").headOption
              .map(expression)
              .orElse(cascadeReceiver.map(_()))
              .getOrElse(thisAst(left))
            saved(syntax, target)(base =>
              saved(syntax, expression(child(left, "index")))(index => {
                def read(): Ast = operator(left, Operators.indexAccess, Seq(base(), index()))
                assign(() => read(), value => operator(syntax, Operators.assignment, Seq(read(), value)))
              })
            )
          case "PropertyAccess" | "PrefixedIdentifier" =>
            val base = children(left, "receiver").headOption
              .map(expression)
              .orElse(cascadeReceiver.map(_()))
              .getOrElse(thisAst(left))
            if (bool(left, "nullAware")) guarded(syntax, base)(access) else saved(syntax, base)(access)
          case _ => access(() => thisAst(left))
        }
      case "BinaryExpression" =>
        if (string(syntax, "operator") == "??")
          saved(syntax, expression(child(syntax, "left")))(ref =>
            operator(
              syntax,
              Operators.conditional,
              Seq(nonNull(syntax, ref()), ref(), expression(child(syntax, "right")))
            )
          )
        else
          operator(
            syntax,
            binaryOperators.getOrElse(string(syntax, "operator"), "<operator>.unknown"),
            Seq(expression(child(syntax, "left")), expression(child(syntax, "right")))
          )
      case "ConditionalExpression" =>
        operator(
          syntax,
          Operators.conditional,
          Seq(
            expression(child(syntax, "condition")),
            expression(child(syntax, "then")),
            expression(child(syntax, "else"))
          )
        )
      case "PrefixExpression" | "PostfixExpression" =>
        val prefix = string(syntax, "kind") == "PrefixExpression"
        val name   = string(syntax, "operator") match {
          case "!"  => if (prefix) Operators.logicalNot else "<operator>.notNullAssert"
          case "~"  => Operators.not
          case "-"  => Operators.minus
          case "+"  => Operators.plus
          case "++" => if (prefix) Operators.preIncrement else Operators.postIncrement
          case "--" => if (prefix) Operators.preDecrement else Operators.postDecrement
          case _    => "<operator>.unknown"
        }
        operator(syntax, name, Seq(expression(child(syntax, "operand"))))
      case "StringInterpolation" | "AdjacentStrings" =>
        operator(syntax, "<operator>.formatString", children(syntax, "element").map(expression))
      case "ListLiteral" | "SetOrMapLiteral" =>
        operator(syntax, Operators.arrayInitializer, children(syntax, "element").map(expression))
      case "MapLiteralEntry" =>
        operator(
          syntax,
          "<operator>.keyValueAssociation",
          Seq(expression(child(syntax, "key")), expression(child(syntax, "value")))
        )
      case "IsExpression" | "AsExpression" =>
        val ast = operator(
          syntax,
          if (string(syntax, "kind") == "AsExpression") Operators.cast else Operators.instanceOf,
          Seq(
            expression(child(syntax, "expression")),
            Ast(NewTypeRef().code(code(child(syntax, "type"))).typeFullName(tpe(child(syntax, "type"))))
          )
        )
        if (string(syntax, "operator") == "is!") operator(syntax, Operators.logicalNot, Seq(ast)) else ast
      case "CascadeExpression" =>
        def cascade(ref: () => Ast): Ast = {
          val previous = cascadeReceiver
          cascadeReceiver = Some(ref)
          val sections = children(syntax, "section").map(expression)
          cascadeReceiver = previous
          block(syntax, sections :+ ref())
        }
        if (nullAware(syntax)) guarded(syntax, expression(child(syntax, "target")))(cascade)
        else saved(syntax, expression(child(syntax, "target")))(cascade)
      case "FunctionExpression"                    => closure(syntax, syntax)
      case "ThrowExpression" | "RethrowExpression" =>
        Ast(located(NewControlStructure().controlStructureType(ControlStructureTypes.THROW).code(code(syntax)), syntax))
          .withChildren(children(syntax, "expression").map(expression))
      case _ => unknown(syntax)
    }
    def control(syntax: Value, typ: String, condition: Ast, body: Ast, otherwise: Option[Ast] = None): Ast = {
      val out = located(NewControlStructure().controlStructureType(typ).code(code(syntax)), syntax)
      var ast = Ast(out).withChild(condition).withConditionEdge(out, condition.root.get).withChild(body)
      ast =
        if (typ == ControlStructureTypes.DO) ast.withDoBodyEdge(out, body.root.get)
        else ast.withTrueBodyEdge(out, body.root.get)
      otherwise.foreach(bodySyntax => ast = ast.withChild(bodySyntax).withFalseBodyEdge(out, bodySyntax.root.get))
      ast
    }
    def statements(syntax: Value): Seq[Ast] = string(syntax, "kind") match {
      case "Block"                        => children(syntax, "statement").flatMap(statements)
      case "VariableDeclarationStatement" => statements(child(syntax, "variables"))
      case "VariableDeclarationList"      => children(syntax, "variable").flatMap(statements)
      case "VariableDeclaration" | "DeclaredIdentifier" | "CatchClauseParameter" =>
        val name  = string(syntax, "name")
        val typ   = tpe(symbol(syntax))
        val local = located(NewLocal().name(name).code(name).typeFullName(typ), syntax)
        declarations(string(syntax, "declaration")) = local
        Seq(Ast(local)) ++ children(syntax, "initializer").map { init =>
          val rhs = expression(init)
          if (bool(symbol(syntax), "final"))
            rhs.nodes
              .collect { case ref: NewMethodRef => ref }
              .lastOption
              .foreach(ref => functionValues(string(syntax, "declaration")) = ref.methodFullName)
          operator(syntax, Operators.assignment, Seq(identifier(syntax, name, string(syntax, "declaration"), typ), rhs))
        }
      case "ExpressionStatement" => Seq(expression(child(syntax, "expression")))
      case "ReturnStatement"     =>
        Seq(args(located(NewReturn().code(code(syntax)), syntax), children(syntax, "expression").map(expression)))
      case "IfStatement" =>
        Seq(
          control(
            syntax,
            ControlStructureTypes.IF,
            expression(child(syntax, "condition")),
            block(syntax, statements(child(syntax, "then"))),
            children(syntax, "else").headOption.map(entryNode => block(entryNode, statements(entryNode)))
          )
        )
      case "WhileStatement" | "DoStatement" =>
        Seq(
          control(
            syntax,
            if (string(syntax, "kind") == "WhileStatement") ControlStructureTypes.WHILE else ControlStructureTypes.DO,
            expression(child(syntax, "condition")),
            block(syntax, statements(child(syntax, "body")))
          )
        )
      case "ForStatement" =>
        val parts = child(syntax, "parts")
        if (string(parts, "kind") == "ForEachParts") {
          val variable    = child(parts, "variable")
          val declaration = if (string(variable, "kind") == "DeclaredIdentifier") statements(variable) else Nil
          val name        = string(variable, "name")
          val loop        =
            saved(syntax, operator(syntax, "<operator>.iterator", Seq(expression(child(parts, "iterable")))))(ref => {
              val assign = operator(
                syntax,
                Operators.assignment,
                Seq(
                  identifier(
                    variable,
                    name,
                    string(variable, "declaration", string(variable, "reference")),
                    tpe(variable)
                  ),
                  field(syntax, ref(), "current")
                )
              )
              control(
                syntax,
                ControlStructureTypes.WHILE,
                call(syntax, "<unresolved>.moveNext", "moveNext", None, Some(ref())),
                block(syntax, Seq(assign) ++ statements(child(syntax, "body")))
              )
            })
          declaration :+ loop
        } else {
          val out =
            located(NewControlStructure().controlStructureType(ControlStructureTypes.FOR).code(code(syntax)), syntax)
          val init = block(syntax, children(parts, "init").flatMap(statements))
          val cond = children(parts, "condition").headOption.map(expression).getOrElse(literal(syntax, "true", "bool"))
          val update = block(syntax, children(parts, "update").map(expression))
          val body   = block(syntax, statements(child(syntax, "body")))
          Seq(
            Ast(out)
              .withChildren(Seq(init, cond, update, body))
              .withForInitEdge(out, init.root.get)
              .withConditionEdge(out, cond.root.get)
              .withForUpdateEdge(out, update.root.get)
              .withForBodyEdge(out, body.root.get)
          )
        }
      case "BreakStatement" | "ContinueStatement" =>
        val out = located(
          NewControlStructure()
            .controlStructureType(
              if (string(syntax, "kind") == "BreakStatement") ControlStructureTypes.BREAK
              else ControlStructureTypes.CONTINUE
            )
            .code(code(syntax)),
          syntax
        )
        Seq(Ast(out))
      case "SwitchStatement" =>
        val cases = children(syntax, "member").flatMap { clauseNode =>
          val label =
            if (string(clauseNode, "kind") == "SwitchDefault") "default" else s"case${clauseNode("offset").num.toInt}"
          Seq(
            Ast(
              located(
                NewJumpTarget().name(label).code(code(clauseNode)).parserTypeName(string(clauseNode, "kind")),
                clauseNode
              )
            )
          ) ++ children(clauseNode, "expression").map(expression) ++ children(clauseNode, "statement").flatMap(
            statements
          ) ++ (if (children(clauseNode, "statement").nonEmpty)
                  Seq(
                    Ast(
                      NewControlStructure().controlStructureType(ControlStructureTypes.BREAK).code("<implicit break>")
                    )
                  )
                else Nil)
        }
        Seq(control(syntax, ControlStructureTypes.SWITCH, expression(child(syntax, "condition")), block(syntax, cases)))
      case "TryStatement" =>
        val out =
          located(NewControlStructure().controlStructureType(ControlStructureTypes.TRY).code(code(syntax)), syntax)
        val body = block(syntax, statements(child(syntax, "body")))
        var ast  = Ast(out).withChild(body).withTryBodyEdge(out, body.root.get)
        children(syntax, "catch").foreach { clauseNode =>
          val bodySyntax = block(
            clauseNode,
            children(clauseNode, "exception").flatMap(statements) ++ children(clauseNode, "stack").flatMap(
              statements
            ) ++ statements(child(clauseNode, "body"))
          )
          ast = ast.withChild(bodySyntax).withCatchBodyEdge(out, bodySyntax.root.get)
        }
        children(syntax, "finally").foreach { fieldSyntax =>
          val bodySyntax = block(fieldSyntax, statements(fieldSyntax))
          ast = ast.withChild(bodySyntax).withFinallyBodyEdge(out, bodySyntax.root.get)
        }
        Seq(ast)
      case "FunctionDeclarationStatement" =>
        val fieldSyntax = child(syntax, "function")
        Seq(closure(fieldSyntax, child(fieldSyntax, "function")))
      case "EmptyStatement" => Nil
      case _                => Seq(expression(syntax))
    }

    def closure(syntax: Value, function: Value): Ast = {
      val id       = string(syntax, "declaration", s"$filename#${syntax("offset").num.toInt}:closure")
      val outer    = declarations.toMap
      val captured = mutable.ArrayBuffer.empty[Ast]
      val ref      = located(NewMethodRef().code(code(syntax)).methodFullName(id).typeFullName(tpe(function)), syntax)
      var refAst   = Ast(ref)
      // Capture only referenced lexical declarations, never members or unrelated locals.
      def references(valueSyntax: Value): Set[String] =
        Set(
          string(valueSyntax, "reference")
        ) ++ (if (
                Set("ThisExpression", "SuperExpression")
                  .contains(string(valueSyntax, "kind")) || Set("FIELD", "GETTER", "SETTER", "METHOD")
                  .contains(string(symbol(valueSyntax, "reference"), "kind"))
              ) Set("this")
              else Set.empty[String]) ++ valueSyntax("children").arr.flatMap(clauseNode =>
          references(nodes(clauseNode("node").num.toInt))
        )
      references(function).toSeq.sorted.filter(outer.contains).foreach { key =>
        outer(key) match {
          case local: NewLocal =>
            val bindingId = s"$id:$key"
            val binding   =
              NewClosureBinding().closureBindingId(bindingId).evaluationStrategy(EvaluationStrategies.BY_REFERENCE)
            val copy =
              NewLocal().name(local.name).code(local.code).typeFullName(local.typeFullName).closureBindingId(bindingId)
            declarations(key) = copy
            captured += Ast(copy)
            refAst = refAst.merge(Ast(binding)).withCaptureEdge(ref, binding).withRefEdge(binding, local)
          case parameter: NewMethodParameterIn =>
            val bindingId = s"$id:$key"
            val binding   =
              NewClosureBinding().closureBindingId(bindingId).evaluationStrategy(EvaluationStrategies.BY_REFERENCE)
            val copy = NewLocal()
              .name(parameter.name)
              .code(parameter.name)
              .typeFullName(parameter.typeFullName)
              .closureBindingId(bindingId)
            declarations(key) = copy
            captured += Ast(copy)
            refAst = refAst.merge(Ast(binding)).withCaptureEdge(ref, binding).withRefEdge(binding, parameter)
          case _ =>
        }
      }
      val previousOwner     = owner
      val previousOwnerType = ownerType
      owner = s"$library:<global>"
      ownerType = "NAMESPACE_BLOCK"
      extraMethods += method(syntax, function, Some(id), captured.toSeq)
      owner = previousOwner
      ownerType = previousOwnerType
      declarations.clear(); declarations ++= outer
      refAst
    }
    def implicitSuper(syntax: Value, id: String, parameters: Seq[Value]): Seq[Ast] = {
      val superId = string(sym(id), "superConstructor")
      if (superId.isEmpty) Nil
      else {
        val superParameters = strings(sym(superId), "parameters")
        val forwarded       =
          parameters.filter(paramNode => string(paramNode, "kind") == "SuperFormalParameter").map { paramNode =>
            val index = superParameters.indexOf(string(paramNode, "superParameter")) + 1
            (
              identifier(
                paramNode,
                string(paramNode, "name"),
                string(paramNode, "declaration"),
                tpe(symbol(paramNode))
              ),
              index
            )
          }
        val defaults = superParameters.zipWithIndex.collect {
          case (id, i) if !forwarded.exists(_._2 == i + 1) && !bool(sym(id), "required") =>
            (literal(syntax, string(sym(id), "defaultValue", "null"), tpe(sym(id))), i + 1)
        }
        val receiver = thisAst(syntax)
        val out      = NewCall()
          .name("<init>")
          .methodFullName(superId)
          .code("super()")
          .typeFullName(string(sym(superId), "returnTypeId", "ANY"))
          .dispatchType(DispatchTypes.STATIC_DISPATCH)
        Seq(
          args(
            out,
            Seq(receiver) ++ forwarded.map(_._1) ++ defaults.map(_._1),
            Seq(0) ++ forwarded.map(_._2) ++ defaults.map(_._2)
          ).withReceiverEdge(out, receiver.root.get)
        )
      }
    }
    def method(syntax: Value, function: Value, forcedId: Option[String] = None, prefix: Seq[Ast] = Nil): Ast = {
      val outer = declarations.toMap
      val id    = forcedId.getOrElse(
        string(syntax, "declaration", s"$filename#${syntax("offset").num.toInt}:${string(syntax, "name")}")
      )
      val target      = sym(id)
      val constructor = string(syntax, "kind") == "ConstructorDeclaration"
      val instance    = ownerType == "TYPE_DECL" && !bool(syntax, "static") && !bool(syntax, "factory")
      val receiver    = if (instance) {
        val out = NewMethodParameterIn()
          .name("this")
          .code("this")
          .index(0)
          .order(0)
          .typeFullName(currentType)
          .evaluationStrategy(EvaluationStrategies.BY_REFERENCE)
          .isVariadic(false)
        declarations("this") = out
        Seq(Ast(out))
      } else Nil
      val parameterNodes = children(function, "parameters").flatMap(paramNode => children(paramNode, "parameter"))
      val parameters     = parameterNodes.zipWithIndex.map { case (wrapped, index) =>
        val paramNode =
          if (string(wrapped, "kind") == "DefaultFormalParameter") child(wrapped, "parameter") else wrapped
        val out = located(
          NewMethodParameterIn()
            .name(string(paramNode, "name"))
            .code(code(wrapped))
            .index(index + 1)
            .order(index + 1)
            .typeFullName(tpe(symbol(paramNode)))
            .evaluationStrategy(EvaluationStrategies.BY_VALUE)
            .isVariadic(false),
          paramNode
        )
        declarations(string(paramNode, "declaration")) = out
        Ast(out)
      }
      val fieldInitializers =
        if (
          constructor && !bool(syntax, "factory") && !children(syntax, "initializer")
            .exists(i => string(i, "kind") == "ConstructorInvocation" && code(i).startsWith("this"))
        ) {
          instanceFields.flatMap(fieldSyntax =>
            children(fieldSyntax, "initializer").map(init =>
              operator(
                fieldSyntax,
                Operators.assignment,
                Seq(field(fieldSyntax, thisAst(fieldSyntax), string(fieldSyntax, "name")), expression(init))
              )
            )
          )
        } else Nil
      val initializers = fieldInitializers ++ parameterNodes.flatMap { wrapped =>
        val paramNode =
          if (string(wrapped, "kind") == "DefaultFormalParameter") child(wrapped, "parameter") else wrapped
        if (string(paramNode, "kind") == "FieldFormalParameter")
          Seq(
            operator(
              paramNode,
              Operators.assignment,
              Seq(
                field(paramNode, thisAst(paramNode), string(paramNode, "name")),
                identifier(
                  paramNode,
                  string(paramNode, "name"),
                  string(paramNode, "declaration"),
                  tpe(symbol(paramNode))
                )
              )
            )
          )
        else Nil
      } ++ children(syntax, "initializer").map { init =>
        if (string(init, "kind") == "ConstructorFieldInitializer")
          operator(
            init,
            Operators.assignment,
            Seq(field(init, thisAst(init), string(child(init, "field"), "name")), expression(child(init, "expression")))
          )
        else expression(init)
      } ++ children(syntax, "redirect").map { redirect =>
        val targetId         = string(redirect, "target")
        val targetParameters = strings(sym(targetId), "parameters")
        val forwarded        = parameters.zip(parameterNodes).map { case (ast, wrapped) =>
          val paramNode = ast.root.get.asInstanceOf[NewMethodParameterIn]
          val node = if (string(wrapped, "kind") == "DefaultFormalParameter") child(wrapped, "parameter") else wrapped
          val ownSymbol   = symbol(node)
          val targetIndex =
            if (bool(ownSymbol, "named"))
              targetParameters.indexWhere(id => string(sym(id), "name") == paramNode.name) + 1
            else paramNode.index
          (identifier(syntax, paramNode.name, string(node, "declaration"), paramNode.typeFullName), targetIndex)
        }
        val out = located(
          NewCall()
            .name(string(redirect, "name"))
            .methodFullName(targetId)
            .code(code(redirect))
            .typeFullName(string(target, "returnTypeId", "ANY"))
            .dispatchType(DispatchTypes.STATIC_DISPATCH),
          redirect
        )
        val allocation = if (bool(sym(targetId), "factory")) Nil else Seq(operator(syntax, Operators.alloc, Nil))
        args(
          located(NewReturn().code(code(redirect)), redirect),
          Seq(args(out, allocation ++ forwarded.map(_._1), allocation.map(_ => 0) ++ forwarded.map(_._2)))
        )
      }
      val superCalls =
        if (
          constructor && !bool(syntax, "factory") && !children(syntax, "initializer")
            .exists(i => string(i, "kind") == "ConstructorInvocation")
        )
          implicitSuper(
            syntax,
            id,
            parameterNodes.map(paramNode =>
              if (string(paramNode, "kind") == "DefaultFormalParameter") child(paramNode, "parameter") else paramNode
            )
          )
        else Nil
      val body     = children(function, "body").headOption
      val bodyAsts = body.toSeq.flatMap { bodySyntax =>
        string(bodySyntax, "kind") match {
          case "BlockFunctionBody"      => statements(child(bodySyntax, "block"))
          case "ExpressionFunctionBody" =>
            Seq(
              args(
                located(NewReturn().code(code(bodySyntax)), bodySyntax),
                Seq(expression(child(bodySyntax, "expression")))
              )
            )
          case "EmptyFunctionBody" => Nil
          case _                   => Seq(unknown(bodySyntax))
        }
      }
      val out = located(
        NewMethod()
          .name(string(syntax, "name", "<lambda>"))
          .fullName(id)
          .code(code(syntax))
          .filename(filename)
          .isExternal(false)
          .signature(s"${string(target, "returnType", "ANY")}(${parameters.size})")
          .astParentType(ownerType)
          .astParentFullName(owner),
        syntax
      )
      val modifiers = Seq(if (bool(target, "private")) ModifierTypes.PRIVATE else ModifierTypes.PUBLIC) ++
        (if (constructor) Seq(ModifierTypes.CONSTRUCTOR) else Nil) ++ (if (!instance) Seq(ModifierTypes.STATIC)
                                                                       else Nil)
      val ast = Ast(out)
        .withChildren(receiver ++ parameters)
        .withChild(block(body.getOrElse(syntax), prefix ++ initializers ++ superCalls ++ bodyAsts))
        .withChild(
          Ast(
            NewMethodReturn()
              .typeFullName(string(target, "returnTypeId", string(target, "returnType", "ANY")))
              .evaluationStrategy(EvaluationStrategies.BY_VALUE)
              .code("RET")
          )
        )
        .withChildren(modifiers.map(memberSyntax => Ast(NewModifier().modifierType(memberSyntax))))
      declarations.clear(); declarations ++= outer
      ast
    }
    def member(syntax: Value): Ast = {
      val out =
        located(NewMember().name(string(syntax, "name")).code(code(syntax)).typeFullName(tpe(symbol(syntax))), syntax)
      declarations(string(syntax, "declaration")) = out
      Ast(out).withChildren(
        (Seq(
          if (bool(symbol(syntax), "private")) ModifierTypes.PRIVATE else ModifierTypes.PUBLIC
        ) ++ (if (bool(symbol(syntax), "static")) Seq(ModifierTypes.STATIC) else Nil)).map(memberSyntax =>
          Ast(NewModifier().modifierType(memberSyntax))
        )
      )
    }
    def initializerMethod(syntax: Value, fields: Seq[Value], id: String, name: String, instance: Boolean): Ast = {
      val old        = declarations.toMap
      val parameters = if (instance) {
        val receiver = NewMethodParameterIn()
          .name("this")
          .code("this")
          .index(0)
          .order(0)
          .typeFullName(currentType)
          .evaluationStrategy(EvaluationStrategies.BY_REFERENCE)
          .isVariadic(false)
        declarations("this") = receiver
        Seq(Ast(receiver))
      } else Nil
      val values = fields.flatMap(fieldSyntax =>
        children(fieldSyntax, "initializer").map(init =>
          operator(
            fieldSyntax,
            Operators.assignment,
            Seq(
              field(
                fieldSyntax,
                if (instance) thisAst(fieldSyntax) else identifier(fieldSyntax, owner, "", owner),
                string(fieldSyntax, "name")
              ),
              expression(init)
            )
          )
        )
      )
      val out = NewMethod()
        .name(name)
        .fullName(id)
        .code(name)
        .filename(filename)
        .isExternal(false)
        .signature("void(0)")
        .astParentType(ownerType)
        .astParentFullName(owner)
      val ast = Ast(out)
        .withChildren(parameters)
        .withChild(block(syntax, values ++ (if (instance) implicitSuper(syntax, id, Nil) else Nil)))
        .withChild(
          Ast(NewMethodReturn().typeFullName("void").code("RET").evaluationStrategy(EvaluationStrategies.BY_VALUE))
        )
        .withChild(Ast(NewModifier().modifierType(if (instance) ModifierTypes.CONSTRUCTOR else ModifierTypes.STATIC)))
      declarations.clear(); declarations ++= old
      ast
    }
    def declaration(syntax: Value): Seq[Ast] = string(syntax, "kind") match {
      case "FunctionDeclaration"                              => Seq(method(syntax, child(syntax, "function")))
      case "MethodDeclaration" | "ConstructorDeclaration"     => Seq(method(syntax, syntax))
      case "TopLevelVariableDeclaration" | "FieldDeclaration" =>
        children(child(syntax, "variables"), "variable").map(member)
      case "ClassDeclaration" =>
        val previousOwner       = owner
        val previousType        = ownerType
        val previousCurrentType = currentType
        val previousFields      = instanceFields
        owner = string(syntax, "declaration")
        ownerType = "TYPE_DECL"
        currentType = string(syntax, "declaration")
        val members   = children(syntax, "member")
        val variables = members
          .filter(memberSyntax => string(memberSyntax, "kind") == "FieldDeclaration")
          .flatMap(memberSyntax => children(child(memberSyntax, "variables"), "variable"))
        instanceFields = variables.filterNot(fieldSyntax => bool(symbol(fieldSyntax), "static"))
        val fields =
          members.filter(memberSyntax => string(memberSyntax, "kind") == "FieldDeclaration").flatMap(declaration)
        val methods =
          members.filterNot(memberSyntax => string(memberSyntax, "kind") == "FieldDeclaration").flatMap(declaration)
        val staticFields = variables.filter(fieldSyntax =>
          bool(symbol(fieldSyntax), "static") && children(fieldSyntax, "initializer").nonEmpty
        )
        val initializers =
          (if (staticFields.nonEmpty)
             Seq(initializerMethod(syntax, staticFields, s"$owner:<clinit>", "<clinit>", false))
           else Nil) ++
            (if (string(syntax, "implicitConstructor").nonEmpty)
               Seq(initializerMethod(syntax, instanceFields, string(syntax, "implicitConstructor"), "<init>", true))
             else Nil)
        val out = located(
          NewTypeDecl()
            .name(string(syntax, "name"))
            .fullName(currentType)
            .code(code(syntax))
            .filename(filename)
            .isExternal(false)
            .inheritsFromTypeFullName(strings(symbol(syntax), "superDeclarations"))
            .astParentType(previousType)
            .astParentFullName(previousOwner),
          syntax
        )
        owner = previousOwner; ownerType = previousType; currentType = previousCurrentType;
        instanceFields = previousFields
        Seq(Ast(out).withChildren(fields ++ methods ++ initializers))
      case _ => Seq(unknown(syntax))
    }
    strings(unit, "unsupportedKinds").foreach(kind => logger.warn(s"Unsupported Dart syntax $kind in $filename"))
    unit("diagnostics").arr.foreach(diagnostic => logger.warn(s"$filename: ${diagnostic("message").str}"))
    val namespace = NewNamespaceBlock().name("<global>").fullName(s"$library:<global>").filename(filename)
    val imports   = children(nodes.head, "directive").map { syntax =>
      val uri = children(syntax, "uri").headOption
        .map(uriSyntax => string(uriSyntax, "value", code(uriSyntax)))
        .getOrElse(library)
      Ast(
        located(
          NewImport()
            .code(code(syntax))
            .importedEntity(uri)
            .importedAs(
              children(syntax, "prefix").headOption.map(paramNode => string(paramNode, "name")).getOrElse(uri)
            )
            .isExplicit(true),
          syntax
        )
      )
    }
    val top    = children(nodes.head, "declaration")
    val fields = top.filter(syntax => string(syntax, "kind") == "TopLevelVariableDeclaration").flatMap(declaration)
    val asts   = top.filterNot(syntax => string(syntax, "kind") == "TopLevelVariableDeclaration").flatMap(declaration)
    val globalFields = top
      .filter(syntax => string(syntax, "kind") == "TopLevelVariableDeclaration")
      .flatMap(syntax => children(child(syntax, "variables"), "variable"))
      .filter(syntax => children(syntax, "initializer").nonEmpty)
    val initializers =
      if (globalFields.nonEmpty) Seq(initializerMethod(nodes.head, globalFields, s"$owner:<clinit>", "<clinit>", false))
      else Nil
    val file = NewFile().name(filename)
    if (!config.disableFileContent) file.content(source)
    Ast.storeInDiffGraph(
      Ast(file).withChild(Ast(namespace).withChildren(imports ++ fields ++ asts ++ initializers ++ extraMethods)),
      diffGraph
    )
  }
}
