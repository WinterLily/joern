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

  private val enumTypes = units
    .flatMap(_("nodes").arr)
    .filter(node => string(node, "kind") == "EnumDeclaration")
    .map(node => string(node, "declaration"))
    .toSet
  private def enumTarget(targetId: String, receiverType: String): String = {
    val target    = sym(targetId)
    val owner     = sym(string(target, "owner"))
    val name      = string(target, "name")
    val generated = enumTypes(receiverType) && ((string(target, "file") == "dart:core/enum.dart" &&
      ((name == "index" && string(owner, "name") == "Enum") ||
        (name == "name" && string(owner, "name") == "EnumName"))) ||
      (name == "toString" && string(owner, "name") == "Object" && string(target, "file") == "dart:core/object.dart"))
    if (generated) s"$receiverType:<enum:$name>" else targetId
  }

  private val lazyMembers = units
    .flatMap(_("nodes").arr)
    .filter { node =>
      string(node, "kind") == "VariableDeclaration" &&
      Set("FIELD", "TOP_LEVEL_VARIABLE").contains(string(symbol(node), "kind")) &&
      (bool(symbol(node), "late") ||
        (bool(symbol(node), "static") && !bool(symbol(node), "const") &&
          node("children").arr.exists(_("role").str == "initializer")))
    }
    .map(string(_, "declaration"))
    .toSet

  override def run(diffGraph: DiffGraphBuilder): Unit = units.foreach { unit =>
    val filename                             = java.net.URI.create(unit("file").str).getPath
    val library                              = string(unit, "library", filename)
    val source                               = unit("source").str
    val nodes                                = unit("nodes").arr
    val declarations                         = mutable.Map.empty[String, NewNode]
    val extraMethods                         = mutable.ArrayBuffer.empty[Ast]
    var owner                                = s"$library:<global>"
    var ownerType                            = "NAMESPACE_BLOCK"
    var currentType                          = "ANY"
    var returnsInstance                      = false
    var instanceFields                       = Seq.empty[Value]
    var initializerContext                   = ""
    val lateLocals                           = mutable.Map.empty[String, Option[String]]
    val functionValues                       = mutable.Map.empty[String, String]
    val boundTargets                         = mutable.Map.empty[String, String]
    var cascadeReceiver: Option[() => Ast]   = None
    var caughtValues: Option[() => Seq[Ast]] = None
    var temporary                            = 0
    val receiverOverrides                    = mutable.Map.empty[Int, () => Ast]
    val labelTargets                         = mutable.Map.empty[String, (String, String)]
    val continueTargets                      = mutable.Map.empty[Int, String]
    val collectionBodies                     = mutable.Map.empty[Int, () => Seq[Ast]]
    val guardedAccesses                      = mutable.Set.empty[Int]
    var patternCache                         = Option.empty[mutable.LinkedHashMap[Vector[String], NewLocal]]

    def children(syntax: Value, role: String): Seq[Value] =
      syntax("children").arr.filter(_("role").str == role).map(entryNode => nodes(entryNode("node").num.toInt)).toSeq
    def child(syntax: Value, role: String): Value = children(syntax, role).head
    def code(syntax: Value): String               =
      source.substring(syntax("offset").num.toInt, (syntax("offset").num + syntax("length").num).toInt)
    def tpe(syntax: Value): String = string(syntax, "typeId", string(syntax, "type", "ANY"))
    val parents = nodes.flatMap(parent => parent("children").arr.map(entry => entry("node").num.toInt -> parent)).toMap
    val functionTypeKinds = Set("GenericFunctionType", "FunctionTypedFormalParameter")
    val typeScopes        = Set(
      "ClassDeclaration",
      "ClassTypeAlias",
      "EnumDeclaration",
      "MixinDeclaration",
      "ExtensionDeclaration",
      "ExtensionTypeDeclaration",
      "GenericTypeAlias",
      "FunctionTypeAlias",
      "FunctionDeclaration",
      "FunctionExpression",
      "MethodDeclaration",
      "ConstructorDeclaration",
      "TypeParameter"
    )
    def functionTypeId(syntax: Value): String =
      string(syntax, "typeDeclaration", s"$filename#${syntax("offset").num.toInt}:FUNCTION_TYPE")
    def signatureScope(syntax: Value): String = parents
      .get(syntax("id").num.toInt)
      .map { parent =>
        val kind = string(parent, "kind")
        if (functionTypeKinds(kind) && children(parent, "typeParameters").nonEmpty) functionTypeId(parent)
        else if (typeScopes(kind)) string(parent, "declaration", signatureScope(parent))
        else signatureScope(parent)
      }
      .getOrElse(s"$library:<global>")
    val signatureTypes = mutable.Map.from(
      nodes
        .filter(syntax => functionTypeKinds(string(syntax, "kind")) && children(syntax, "typeParameters").nonEmpty)
        .groupBy(signatureScope)
    )
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
    val booleanOperators = Set(
      Operators.equals,
      Operators.notEquals,
      Operators.lessThan,
      Operators.lessEqualsThan,
      Operators.greaterThan,
      Operators.greaterEqualsThan,
      Operators.logicalAnd,
      Operators.logicalOr,
      Operators.logicalNot,
      Operators.instanceOf,
      "<operator>.isInitialized",
      "<operator>.patternShape"
    )
    def operator(syntax: Value, name: String, values: Seq[Ast]): Ast = {
      val sourceType = tpe(syntax)
      val resultType =
        if (
          booleanOperators(name) && sourceType != "bool" &&
          !(sourceType.startsWith("dart:core") && sourceType.endsWith(":CLASS:bool"))
        ) "bool"
        else sourceType
      args(
        located(
          NewCall()
            .name(name)
            .methodFullName(io.joern.x2cpg.frontendspecific.DartLanguage.operatorName(name))
            .code(code(syntax))
            .typeFullName(resultType)
            .dispatchType(DispatchTypes.STATIC_DISPATCH),
          syntax
        ),
        values
      )
    }
    def literal(syntax: Value, value: String, typ: String = "ANY"): Ast =
      Ast(located(NewLiteral().code(value).typeFullName(typ), syntax))
    def identifier(syntax: Value, name: String, id: String, typ: String): Ast = {
      val out = located(NewIdentifier().name(name).code(name).typeFullName(typ), syntax)
      declarations.get(id).fold(Ast(out))(target => Ast(out).withRefEdge(out, target))
    }
    def thisAst(syntax: Value): Ast =
      identifier(syntax, "this", "this", string(sym(currentType), "extendedType", currentType))
    def field(syntax: Value, receiver: Ast, name: String): Ast =
      operator(
        syntax,
        Operators.fieldAccess,
        Seq(receiver, Ast(located(NewFieldIdentifier().canonicalName(name).code(name), syntax)))
      )
    def savedSequence(syntax: Value, value: Ast)(body: (() => Ast) => Seq[Ast]): Ast = {
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
      val assignment = operator(syntax, Operators.assignment, Seq(ref(), value))
      // Distinct synthetic code prevents code-based reaching definitions from aliasing the enclosing expression.
      assignment.root.collect { case call: NewCall => call.code = s"$name = ${code(syntax)}" }
      block(syntax, Seq(Ast(local), assignment) ++ body(() => ref()))
    }
    def cascadeBase(syntax: Value): Option[Ast] =
      cascadeReceiver.filter(_ => bool(syntax, "cascaded")).map(_())
    def saved(syntax: Value, value: Ast)(body: (() => Ast) => Ast): Ast =
      savedSequence(syntax, value)(ref => Seq(body(ref)))
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
      receiver: Option[Ast] = None,
      callableReceiver: Boolean = false
    ): Ast = {
      val selectedTarget = receiver.map(value => enumTarget(targetId, astType(value))).getOrElse(targetId)
      val target         = sym(boundTargets.getOrElse(targetId, targetId))
      val params         = strings(target, "parameters")
      val actual         = argumentList.toSeq.flatMap(arguments => children(arguments, "argument"))
      val bindings       = argumentList.toSeq.flatMap(_("bindings").arr).zipWithIndex.map { case (bodySyntax, i) =>
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
          .methodFullName(if (selectedTarget.nonEmpty) selectedTarget else s"<unresolved>.$name")
          .code(code(syntax))
          .typeFullName(tpe(syntax))
          .dispatchType(
            if (receiver.nonEmpty && string(sym(string(target, "owner")), "kind") != "EXTENSION")
              DispatchTypes.DYNAMIC_DISPATCH
            else DispatchTypes.STATIC_DISPATCH
          ),
        syntax
      )
      val base   = receiver.filterNot(_ => callableReceiver)
      val values = base.toSeq ++ explicit ++ defaults.map(_._1)
      val ast    = args(out, values, base.toSeq.map(_ => 0) ++ bindings ++ defaults.map(_._2))
      receiver.flatMap(_.root).fold(ast) { receiverRoot =>
        // The callable is evaluated, but is not a mutable `this` argument.
        val withReceiver = if (callableReceiver) {
          values.flatMap(_.root).collect { case expr: ExpressionNew => expr.order += 1 }
          receiverRoot match {
            case expr: ExpressionNew => expr.order = 1; expr.argumentIndex = -1
            case _                   =>
          }
          ast.withChild(receiver.get)
        } else ast
        withReceiver.withReceiverEdge(out, receiverRoot)
      }
    }
    // Keep allocation, initialization and the resulting object tied to the same local.
    def newInstance(
      syntax: Value,
      targetId: String,
      name: String,
      arguments: Option[Value],
      initialize: (() => Ast) => Seq[Ast] = _ => Nil
    ): Ast =
      if (bool(sym(targetId), "factory")) saved(syntax, call(syntax, targetId, name, arguments))(ref => ref())
      else
        savedSequence(syntax, operator(syntax, Operators.alloc, Nil)) { receiver =>
          initialize(receiver) ++ Seq(call(syntax, targetId, name, arguments, Some(receiver())), receiver())
        }
    def methodRef(syntax: Value, id: String): Ast = {
      val ref = located(
        NewMethodRef().code(code(syntax)).methodFullName(functionValues.getOrElse(id, id)).typeFullName(tpe(syntax)),
        syntax
      )
      Ast(ref)
    }
    def boundMethodRef(syntax: Value, targetId: String, receiver: Ast): Ast = saved(syntax, receiver) { outerRef =>
      val id     = s"$filename#${syntax("offset").num.toInt}:bound$initializerContext"
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
          .methodFullName(enumTarget(targetId, astType(receiver)))
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
          if (bool(target, "synthetic") && !lazyMembers.contains(string(target, "variable"))) field(syntax, base, name)
          else call(syntax, id, name, None, if (bool(target, "static")) None else Some(base))
        case "FIELD" | "TOP_LEVEL_VARIABLE" =>
          if (lazyMembers.contains(id))
            call(syntax, string(target, "getter"), name, None, if (bool(target, "static")) None else Some(base))
          else field(syntax, base, name)
        case _
            if id.isEmpty && Set("PropertyAccess", "PrefixedIdentifier", "PatternField").contains(
              string(syntax, "kind")
            ) =>
          field(syntax, base, name)
        case _ if lateLocals.contains(id) => lateLocalRead(syntax, id, name)
        case _                            => identifier(syntax, name, id, tpe(syntax))
      }
    }
    def lateFailure(syntax: Value, name: String): Ast = Ast(
      NewControlStructure().controlStructureType(ControlStructureTypes.THROW).code(s"LateInitializationError($name)")
    ).withChild(operator(syntax, "<operator>.lateInitializationError", Seq(literal(syntax, name, "String"))))
    def initializeIfNeeded(
      syntax: Value,
      name: String,
      location: () => Ast,
      initializer: Option[Ast],
      isFinal: Boolean
    ): Ast = {
      // Initialization state is separate from the stored value, which may legitimately be null.
      def initialized(): Ast = operator(syntax, "<operator>.isInitialized", Seq(location()))
      val initialize         = initializer
        .map { init =>
          savedSequence(syntax, init) { value =>
            (if (isFinal)
               Seq(
                 control(syntax, ControlStructureTypes.IF, initialized(), block(syntax, Seq(lateFailure(syntax, name))))
               )
             else Nil) :+
              operator(syntax, Operators.assignment, Seq(location(), value()))
          }
        }
        .getOrElse(lateFailure(syntax, name))
      control(
        syntax,
        ControlStructureTypes.IF,
        operator(syntax, Operators.logicalNot, Seq(initialized())),
        block(syntax, Seq(initialize))
      )
    }
    def lateLocalRead(syntax: Value, id: String, name: String): Ast = {
      def location(): Ast = identifier(syntax, name, id, tpe(sym(id)))
      val initialize      = lateLocals(id).map(thunk =>
        call(
          syntax,
          thunk,
          "<late-init>",
          None,
          Some(identifier(syntax, thunk, thunk, "Function")),
          callableReceiver = true
        )
      )
      block(
        syntax,
        Seq(initializeIfNeeded(syntax, name, () => location(), initialize, bool(sym(id), "final")), location())
      )
    }
    def lateLocalWrite(syntax: Value, id: String, name: String, value: Ast): Ast =
      savedSequence(syntax, value) { assigned =>
        def location(): Ast = identifier(syntax, name, id, tpe(sym(id)))
        (if (bool(sym(id), "final"))
           Seq(
             control(
               syntax,
               ControlStructureTypes.IF,
               operator(syntax, "<operator>.isInitialized", Seq(location())),
               block(syntax, Seq(lateFailure(syntax, name)))
             )
           )
         else Nil) ++
          Seq(operator(syntax, Operators.assignment, Seq(location(), assigned())), assigned())
      }
    def lateLocalInitializer(syntax: Value, init: Value, id: String): Seq[Ast] = {
      val thunkId = s"$id:<late-init>$initializerContext"
      val thunk   = NewLocal().name(thunkId).code(thunkId).typeFullName("Function")
      declarations(thunkId) = thunk
      lateLocals(id) = Some(thunkId)
      val value = capturedClosure(syntax, init, thunkId) { captures =>
        val method = located(
          NewMethod()
            .name("<late-init>")
            .fullName(thunkId)
            .code(code(init))
            .filename(filename)
            .isExternal(false)
            .signature(s"${tpe(init)}(0)")
            .astParentType(ownerType)
            .astParentFullName(owner),
          init
        )
        Ast(method)
          .withChild(block(init, captures :+ args(NewReturn().code(code(init)), Seq(expression(init)))))
          .withChild(
            Ast(
              NewMethodReturn()
                .typeFullName(tpe(init))
                .code("RET")
                .evaluationStrategy(EvaluationStrategies.BY_VALUE)
            )
          )
          .withChild(Ast(NewModifier().modifierType(ModifierTypes.STATIC)))
      }
      value.root.collect { case ref: NewMethodRef => ref.typeFullName = "Function" }
      Seq(
        Ast(thunk),
        operator(syntax, Operators.assignment, Seq(identifier(syntax, thunkId, thunkId, "Function"), value))
      )
    }
    def interpolatedValue(syntax: Value, value: Ast): Ast = {
      def converted(receiver: Ast): Ast = {
        val invocation = call(syntax, string(syntax, "conversionTarget"), "toString", None, Some(receiver))
        invocation.root.collect { case out: NewCall => out.typeFullName = "String" }
        invocation
      }
      if (!bool(syntax, "stringConversion")) value
      else if (bool(syntax, "conversionNullable")) {
        val result = saved(syntax, value) { ref =>
          val choice = operator(
            syntax,
            Operators.conditional,
            Seq(nonNull(syntax, ref()), converted(ref()), literal(syntax, "\"null\"", "String"))
          )
          choice.root.collect { case out: NewCall => out.typeFullName = "String" }
          choice
        }
        result.root.collect { case out: NewBlock => out.typeFullName = "String" }
        result
      } else converted(value)
    }
    def stringAssembly(syntax: Value): Ast = {
      def unparenthesized(node: Value): Value =
        if (string(node, "kind") == "ParenthesizedExpression") unparenthesized(child(node, "expression")) else node
      def fragments(node: Value): Seq[Value] = string(node, "kind") match {
        case "StringInterpolation" | "AdjacentStrings" => children(node, "element").flatMap(fragments)
        case "InterpolationExpression"                 =>
          val value = unparenthesized(child(node, "expression"))
          if (Set("StringInterpolation", "AdjacentStrings")(string(value, "kind"))) fragments(value) else Seq(node)
        case "StringLiteral" if string(node, "value").isEmpty => Nil
        case _                                                => Seq(node)
      }
      def concatenate(parts: Seq[Ast]): Ast =
        parts.headOption.fold(literal(syntax, "\"\"", tpe(syntax))) { first =>
          parts.tail.foldLeft(first)((left, right) => operator(syntax, Operators.addition, Seq(left, right)))
        }
      def assemble(remaining: List[Value], values: Vector[() => Ast]): Ast = remaining match {
        case head :: tail if string(head, "kind") == "InterpolationExpression" =>
          // The pinned VM evaluates all embedded expressions before invoking their string conversions.
          saved(syntax, expression(child(head, "expression"))) { ref =>
            assemble(tail, values :+ (() => interpolatedValue(head, ref())))
          }
        case head :: tail => assemble(tail, values :+ (() => literal(head, code(head), tpe(syntax))))
        case Nil          => concatenate(values.map(_()))
      }
      if (config.environment == "web") concatenate(fragments(syntax).map { fragment =>
        if (string(fragment, "kind") == "InterpolationExpression") expression(fragment)
        else literal(fragment, code(fragment), tpe(syntax))
      })
      else assemble(fragments(syntax).toList, Vector.empty)
    }
    def userOperator(id: String): Boolean =
      string(sym(id), "kind") == "METHOD" && !string(sym(id), "file").startsWith("dart:")
    def astType(ast: Ast): String = ast.root
      .map {
        case node: NewCall       => node.typeFullName
        case node: NewIdentifier => node.typeFullName
        case node: NewLiteral    => node.typeFullName
        case node: NewBlock      => node.typeFullName
        case node: NewMethodRef  => node.typeFullName
        case node: NewTypeRef    => node.typeFullName
        case _                   => "ANY"
      }
      .getOrElse("ANY")
    def operatorDispatch(id: String, receiverType: String): Boolean = {
      val primitive = Set("int", "double", "num", "bool", "String", "Null").exists(name =>
        receiverType == name || (receiverType.startsWith("dart:core") && receiverType.endsWith(s":CLASS:$name"))
      )
      userOperator(id) || (id.isEmpty && Set("dynamic", "ANY").contains(receiverType)) ||
      (string(sym(id), "name") == "==" && !primitive)
    }
    def resolvedOperator(syntax: Value, name: String, values: Seq[Ast], targetId: String): Ast = {
      val intrinsic = Set(Operators.logicalAnd, Operators.logicalOr, Operators.logicalNot, "<operator>.notNullAssert")
      val equality  = name == Operators.equals || name == Operators.notEquals
      val hasNull   = values.exists(_.root.exists {
        case literal: NewLiteral => literal.code == "null"
        case _                   => false
      })
      def invoke(arguments: Seq[Ast]): Ast = {
        val token    = string(syntax, "operator").stripSuffix("=")
        val fallback = name match {
          case Operators.indexAccess => "[]"
          case Operators.assignment  => "[]="
          case Operators.notEquals   => "=="
          case _                     =>
            if (binaryOperators.get(token).contains(name)) token
            else binaryOperators.find(_._2 == name).map(_._1).getOrElse(token)
        }
        val methodName = string(sym(targetId), "name", fallback)
        val out        = located(
          NewCall()
            .name(methodName)
            .methodFullName(if (targetId.nonEmpty) targetId else s"<unresolved>.$methodName")
            .code(code(syntax))
            .typeFullName(if (booleanOperators(name)) "bool" else tpe(syntax))
            .dispatchType(
              if (string(sym(string(sym(targetId), "owner")), "kind") == "EXTENSION")
                DispatchTypes.STATIC_DISPATCH
              else DispatchTypes.DYNAMIC_DISPATCH
            ),
          syntax
        )
        args(out, arguments, arguments.indices).withReceiverEdge(out, arguments.head.root.get)
      }
      if (intrinsic.contains(name) || !operatorDispatch(targetId, astType(values.head)) || equality && hasNull)
        operator(syntax, name, values)
      else if (name == Operators.notEquals)
        operator(syntax, Operators.logicalNot, Seq(resolvedOperator(syntax, Operators.equals, values, targetId)))
      else if (name == Operators.equals) {
        def booleanResult(ast: Ast): Ast = {
          ast.root.foreach {
            case block: NewBlock => block.typeFullName = "bool"
            case call: NewCall   => call.typeFullName = "bool"
            case _               =>
          }
          ast
        }
        booleanResult(saved(syntax, values.head) { left =>
          booleanResult(saved(syntax, values(1)) { right =>
            booleanResult(
              operator(
                syntax,
                Operators.conditional,
                Seq(
                  operator(syntax, Operators.logicalAnd, Seq(nonNull(syntax, left()), nonNull(syntax, right()))),
                  invoke(Seq(left(), right())),
                  operator(syntax, Operators.equals, Seq(left(), right()))
                )
              )
            )
          })
        })
      } else invoke(values)
    }
    def update(syntax: Value, left: Value)(assign: (() => Ast, Ast => Ast) => Ast): Ast = {
      def access(receiver: () => Ast): Ast = {
        val readId                 = string(syntax, "read", string(left, "reference"))
        val writeId                = string(syntax, "write", string(left, "reference"))
        def read(): Ast            = reference(left, readId, string(left, "name"), Some(receiver()))
        def write(value: Ast): Ast = {
          if (lateLocals.contains(writeId)) lateLocalWrite(syntax, writeId, string(left, "name"), value)
          else if (
            string(sym(writeId), "kind") == "SETTER" &&
            (!bool(sym(writeId), "synthetic") || lazyMembers.contains(string(sym(writeId), "variable")))
          ) {
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
            savedSequence(syntax, value) { assigned =>
              val invocation =
                if (bool(sym(writeId), "static")) args(out, Seq(assigned()))
                else {
                  val base = receiver()
                  args(out, Seq(base, assigned()), Seq(0, 1)).withReceiverEdge(out, base.root.get)
                }
              Seq(invocation, assigned())
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
            .orElse(cascadeBase(left))
            .getOrElse(thisAst(left))
          saved(syntax, target)(base =>
            saved(syntax, expression(child(left, "index")))(index => {
              val readId      = string(syntax, "read", string(left, "operatorTarget"))
              val writeId     = string(syntax, "write", string(left, "operatorTarget"))
              def read(): Ast = resolvedOperator(left, Operators.indexAccess, Seq(base(), index()), readId)
              assign(
                () => read(),
                value =>
                  if (operatorDispatch(writeId, astType(target)))
                    savedSequence(syntax, value)(assigned =>
                      Seq(
                        resolvedOperator(syntax, Operators.assignment, Seq(base(), index(), assigned()), writeId),
                        assigned()
                      )
                    )
                  else operator(syntax, Operators.assignment, Seq(read(), value))
              )
            })
          )
        case "PropertyAccess" | "PrefixedIdentifier" =>
          val base = children(left, "receiver").headOption
            .map(expression)
            .orElse(cascadeBase(left))
            .getOrElse(thisAst(left))
          if (nullAware(left)) guarded(syntax, base)(access) else saved(syntax, base)(access)
        case _ => access(() => thisAst(left))
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
      case "IndexExpression"                        => children(syntax, "target").headOption
      case "AssignmentExpression"                   => children(syntax, "left").headOption
      case "PrefixExpression" | "PostfixExpression" => children(syntax, "operand").headOption
      case _                                        => None
    }
    def firstNullAware(syntax: Value): Option[Value] = chainReceiver(syntax).flatMap { receiver =>
      firstNullAware(receiver).orElse(if (nullAware(syntax)) Some(syntax) else None)
    }
    def collection(syntax: Value): Ast = {
      val elements = children(syntax, "element")
      val expanded = elements.exists(element =>
        Set("ForElement", "IfElement", "SpreadElement", "NullAwareElement")
          .contains(string(element, "kind")) || bool(element, "nullAwareKey") || bool(element, "nullAwareValue")
      )
      if (!expanded) operator(syntax, Operators.arrayInitializer, elements.map(expression))
      else {
        val kind  = string(syntax, "collectionKind", "list")
        val empty = operator(syntax, Operators.arrayInitializer, Nil)
        empty.root.collect { case call: NewCall => call.code = (if (kind == "list") "[]" else "{}") }
        savedSequence(syntax, empty) { target =>
          def append(element: Value, value: Ast, spread: Boolean = false): Ast = {
            val name =
              if (spread) "<operator>.collectionExtend"
              else
                kind match {
                  case "map" => "<operator>.mapPut"
                  case "set" => "<operator>.setAdd"
                  case _     => "<operator>.listAppend"
                }
            val updated = operator(element, name, Seq(target(), value))
            updated.root.collect { case call: NewCall => call.typeFullName = tpe(syntax) }
            val left       = target()
            val assignment = operator(element, Operators.assignment, Seq(left, updated))
            assignment.root.collect { case call: NewCall =>
              call.code = s"${left.root.get.asInstanceOf[NewIdentifier].name} += ${code(element)}"
              call.typeFullName = tpe(syntax)
            }
            assignment
          }
          def emit(element: Value): Seq[Ast] = string(element, "kind") match {
            case "ForElement" =>
              val id = element("id").num.toInt
              collectionBodies(id) = () => emit(child(element, "body"))
              try statements(element)
              finally collectionBodies.remove(id)
            case "IfElement" =>
              Seq(
                control(
                  element,
                  ControlStructureTypes.IF,
                  condition(element),
                  block(element, emit(child(element, "then"))),
                  children(element, "else").headOption.map(other => block(other, emit(other)))
                )
              )
            case "NullAwareElement" =>
              Seq(
                saved(element, expression(child(element, "expression")))(value =>
                  control(
                    element,
                    ControlStructureTypes.IF,
                    nonNull(element, value()),
                    block(element, Seq(append(element, value())))
                  )
                )
              )
            case "SpreadElement" =>
              def extend(value: Ast): Ast =
                append(element, operator(element, "<operator>.spread", Seq(value)), spread = true)
              Seq(
                if (bool(element, "nullAware"))
                  saved(element, expression(child(element, "expression")))(value =>
                    control(
                      element,
                      ControlStructureTypes.IF,
                      nonNull(element, value()),
                      block(element, Seq(extend(value())))
                    )
                  )
                else extend(expression(child(element, "expression")))
              )
            case "MapLiteralEntry" if bool(element, "nullAwareKey") || bool(element, "nullAwareValue") =>
              Seq(saved(element, expression(child(element, "key"))) { key =>
                def entry(): Ast = saved(element, expression(child(element, "value"))) { value =>
                  val add = append(element, operator(element, "<operator>.keyValueAssociation", Seq(key(), value())))
                  if (bool(element, "nullAwareValue"))
                    control(element, ControlStructureTypes.IF, nonNull(element, value()), block(element, Seq(add)))
                  else add
                }
                if (bool(element, "nullAwareKey"))
                  control(element, ControlStructureTypes.IF, nonNull(element, key()), block(element, Seq(entry())))
                else entry()
              })
            case _ => Seq(append(element, expression(element)))
          }
          elements.flatMap(emit) :+ target()
        }
      }
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
    def patternScope(syntax: Value)(body: => Ast): Ast = {
      val previous = patternCache
      val storage  = mutable.LinkedHashMap.empty[Vector[String], NewLocal]
      patternCache = Some(storage)
      val result = body
      patternCache = previous
      if (storage.isEmpty) result else block(syntax, storage.values.toSeq.map(Ast(_)) :+ result)
    }
    def patternAccess(syntax: Value, key: Vector[String], value: Ast): Ast = patternCache match {
      case None          => value
      case Some(storage) =>
        val local = storage.getOrElseUpdate(
          key, {
            temporary += 1
            val name  = s"<pattern>$temporary"
            val local = located(NewLocal().name(name).code(name).typeFullName(astType(value)), syntax)
            declarations(name) = local
            local
          }
        )
        def ref(): Ast = identifier(syntax, local.name, local.name, local.typeFullName)
        val assignment = operator(syntax, Operators.assignment, Seq(ref(), value))
        assignment.root.collect { case call: NewCall => call.code = s"${local.name} = ${code(syntax)}" }
        val result = block(
          syntax,
          Seq(
            control(
              syntax,
              ControlStructureTypes.IF,
              operator(syntax, Operators.logicalNot, Seq(operator(syntax, "<operator>.isInitialized", Seq(ref())))),
              block(syntax, Seq(assignment))
            ),
            ref()
          )
        )
        result.root.collect { case block: NewBlock => block.typeFullName = local.typeFullName }
        result
    }
    def patternMember(syntax: Value, targetId: String, name: String, values: Seq[Ast]): Ast = {
      val target = sym(targetId)
      val out    = located(
        NewCall()
          .name(name)
          .methodFullName(if (targetId.nonEmpty) targetId else s"<unresolved>.$name")
          .code(code(syntax))
          .typeFullName(string(target, "returnTypeId", string(target, "returnType", "ANY")))
          .dispatchType(DispatchTypes.DYNAMIC_DISPATCH),
        syntax
      )
      args(out, values, values.indices).withReceiverEdge(out, values.head.root.get)
    }
    def patternInvocation(syntax: Value, parent: Vector[String], name: String): Vector[String] = {
      val extension = string(syntax, "extensionTarget")
      val member    =
        if (extension.isEmpty) name
        else if (syntax.obj.contains("extensionArgumentIdentities"))
          s"extension:$extension:${strings(syntax, "extensionArgumentIdentities").mkString(",")}:$name"
        else s"extension:${syntax("id")}:$name"
      parent :+ member
    }
    def pattern(syntax: Value, value: () => Ast, parent: Vector[String] = Vector("this")): Ast = {
      def and(values: Seq[Ast]): Ast = values
        .reduceOption((left, right) => operator(syntax, Operators.logicalAnd, Seq(left, right)))
        .getOrElse(literal(syntax, "true", "bool"))
      def typeCheck: Seq[Ast] = children(syntax, "type").map(typ =>
        operator(syntax, Operators.instanceOf, Seq(value(), Ast(NewTypeRef().code(code(typ)).typeFullName(tpe(typ)))))
      )
      string(syntax, "kind") match {
        case "DeclaredVariablePattern" | "AssignedVariablePattern" =>
          val id     = string(syntax, "declaration", string(syntax, "reference"))
          val name   = string(syntax, "name")
          val locals = if (string(syntax, "kind") == "DeclaredVariablePattern" && !declarations.contains(id)) {
            val local = located(NewLocal().name(name).code(name).typeFullName(tpe(sym(id))), syntax)
            declarations(id) = local
            Seq(Ast(local))
          } else Nil
          and(
            typeCheck :+ block(
              syntax,
              locals ++ Seq(
                operator(syntax, Operators.assignment, Seq(identifier(syntax, name, id, tpe(sym(id))), value())),
                literal(syntax, "true", "bool")
              )
            )
          )
        case "WildcardPattern"                       => and(typeCheck)
        case "ConstantPattern" | "RelationalPattern" =>
          val constant   = string(syntax, "kind") == "ConstantPattern"
          val token      = if (constant) "constant==" else string(syntax, "operator").replace("!=", "==")
          val name       = if (constant) Operators.equals else binaryOperators.getOrElse(token, Operators.equals)
          val argument   = expression(child(syntax, "expression"))
          val values     = if (constant) Seq(argument, value()) else Seq(value(), argument)
          val invocation = resolvedOperator(syntax, name, values, string(syntax, "operatorTarget"))
          val identity   = string(syntax, "constantIdentity", s"source:${syntax("id")}")
          val cached     =
            patternAccess(syntax, patternInvocation(syntax, parent, s"comparison:$token:$identity"), invocation)
          if (string(syntax, "operator") == "!=") operator(syntax, Operators.logicalNot, Seq(cached)) else cached
        case "LogicalAndPattern" | "LogicalOrPattern" =>
          operator(
            syntax,
            if (string(syntax, "kind") == "LogicalAndPattern") Operators.logicalAnd else Operators.logicalOr,
            Seq(pattern(child(syntax, "left"), value, parent), pattern(child(syntax, "right"), value, parent))
          )
        case "ParenthesizedPattern" => pattern(child(syntax, "pattern"), value, parent)
        case "CastPattern"          =>
          val typ = child(syntax, "type")
          saved(
            syntax,
            operator(syntax, Operators.cast, Seq(value(), Ast(NewTypeRef().code(code(typ)).typeFullName(tpe(typ)))))
          )(ref => pattern(child(syntax, "pattern"), ref, parent))
        case "NullCheckPattern"  => and(Seq(nonNull(syntax, value()), pattern(child(syntax, "pattern"), value, parent)))
        case "NullAssertPattern" =>
          saved(syntax, operator(syntax, "<operator>.notNullAssert", Seq(value())))(ref =>
            pattern(child(syntax, "pattern"), ref, parent)
          )
        case "RecordPattern" | "ObjectPattern" =>
          val shape    = operator(syntax, "<operator>.patternShape", Seq(value(), literal(syntax, code(syntax))))
          var position = 0
          and(typeCheck ++ Seq(shape) ++ children(syntax, "field").map { entry =>
            val name = Option(string(entry, "name")).filter(_.nonEmpty).getOrElse {
              position += 1
              s"$$$position"
            }
            val access =
              if (string(syntax, "kind") == "ObjectPattern")
                reference(entry, string(entry, "reference"), name, Some(value()))
              else field(entry, value(), name)
            val key = patternInvocation(entry, parent, name)
            saved(entry, patternAccess(entry, key, access))(ref => pattern(child(entry, "pattern"), ref, key))
          })
        case "ListPattern" =>
          val elements = children(syntax, "element")
          val rest     = elements.indexWhere(entry => string(entry, "kind") == "RestPatternElement")
          val minimum  = elements.size - (if (rest >= 0) 1 else 0)
          def wildcard(entry: Value): Boolean =
            string(entry, "kind") == "WildcardPattern" && children(entry, "type").isEmpty
          def length(): Ast = patternAccess(
            syntax,
            parent :+ "length",
            patternMember(syntax, string(syntax, "lengthTarget"), "length", Seq(value()))
          )
          val typeTest = operator(
            syntax,
            Operators.instanceOf,
            Seq(
              value(),
              Ast(
                NewTypeRef()
                  .code(string(syntax, "requiredType", "List"))
                  .typeFullName(string(syntax, "requiredTypeId", "List"))
              )
            )
          )
          val sizeTest =
            if (rest < 0 || minimum > 0)
              Seq(
                operator(
                  syntax,
                  if (rest < 0) Operators.equals else Operators.greaterEqualsThan,
                  Seq(length(), literal(syntax, minimum.toString, "int"))
                )
              )
            else Nil
          val extracted = elements.zipWithIndex.flatMap { case (entry, index) =>
            if (index == rest) children(entry, "pattern").filterNot(wildcard).map { inner =>
              val trailing = elements.size - index - 1
              val key      = parent :+ s"rest:$index:$trailing"
              val end      =
                if (trailing == 0) literal(entry, "null", "Null")
                else operator(entry, Operators.subtraction, Seq(length(), literal(entry, trailing.toString, "int")))
              val slice = patternMember(
                entry,
                string(syntax, "sublistTarget"),
                "sublist",
                Seq(value(), literal(entry, index.toString, "int"), end)
              )
              saved(entry, patternAccess(entry, key, slice))(ref => pattern(inner, ref, key))
            }
            else if (wildcard(entry)) Nil
            else {
              val fromEnd  = rest >= 0 && index > rest
              val position = if (fromEnd) elements.size - index - 1 else index
              val key      = parent :+ s"${if (fromEnd) "tail" else "index"}:$position"
              val offset   =
                if (fromEnd)
                  operator(entry, Operators.subtraction, Seq(length(), literal(entry, (position + 1).toString, "int")))
                else literal(entry, position.toString, "int")
              val element = patternMember(entry, string(syntax, "indexTarget"), "[]", Seq(value(), offset))
              Seq(saved(entry, patternAccess(entry, key, element))(ref => pattern(entry, ref, key)))
            }
          }
          and(Seq(typeTest) ++ sizeTest ++ extracted)
        case "MapPattern" =>
          val extracted = children(syntax, "element").flatMap { entry =>
            string(entry, "kind") match {
              case "MapPatternEntry" =>
                val identity = string(entry, "keyIdentity", s"source:${entry("id")}")
                val key      = parent :+ s"map:$identity"
                Seq(saved(entry, expression(child(entry, "key"))) { constant =>
                  val access = patternMember(entry, string(syntax, "indexTarget"), "[]", Seq(value(), constant()))
                  saved(entry, patternAccess(entry, key, access)) { ref =>
                    val acceptsNull = operator(
                      entry,
                      Operators.instanceOf,
                      Seq(
                        literal(entry, "null", "Null"),
                        Ast(
                          NewTypeRef()
                            .code(string(syntax, "valueType", "dynamic"))
                            .typeFullName(string(syntax, "valueType", "dynamic"))
                        )
                      )
                    )
                    val presence = patternAccess(
                      entry,
                      parent :+ s"mapContains:$identity",
                      patternMember(entry, string(syntax, "containsKeyTarget"), "containsKey", Seq(value(), constant()))
                    )
                    and(
                      Seq(
                        operator(
                          entry,
                          Operators.logicalOr,
                          Seq(nonNull(entry, ref()), operator(entry, Operators.logicalAnd, Seq(acceptsNull, presence)))
                        ),
                        pattern(child(entry, "pattern"), ref, key)
                      )
                    )
                  }
                })
              case "RestPatternElement" if children(entry, "pattern").isEmpty => Nil
              case _                                                          => Seq(unknown(entry))
            }
          }
          val typeTest = operator(
            syntax,
            Operators.instanceOf,
            Seq(
              value(),
              Ast(
                NewTypeRef()
                  .code(string(syntax, "requiredType", "Map"))
                  .typeFullName(string(syntax, "requiredTypeId", "Map"))
              )
            )
          )
          and(Seq(typeTest) ++ extracted)
        case _ => unknown(syntax)
      }
    }
    def guard(syntax: Value, value: () => Ast): Ast = {
      val matched = pattern(child(syntax, "pattern"), value)
      children(syntax, "when").headOption.fold(matched)(condition =>
        operator(syntax, Operators.logicalAnd, Seq(matched, expression(condition)))
      )
    }
    def condition(syntax: Value): Ast = children(syntax, "case").headOption match {
      case Some(clause) =>
        patternScope(syntax) {
          saved(syntax, expression(child(syntax, "condition")))(ref => guard(clause, ref))
        }
      case None => expression(child(syntax, "condition"))
    }
    def expressionBody(syntax: Value): Ast = string(syntax, "kind") match {
      case "RecordLiteral" =>
        savedSequence(syntax, operator(syntax, "<operator>.record", Nil)) { ref =>
          var position = 0
          children(syntax, "field").map { entry =>
            val name =
              if (string(entry, "kind") == "NamedExpression") string(entry, "name")
              else {
                position += 1
                s"$$$position"
              }
            operator(entry, Operators.assignment, Seq(field(entry, ref(), name), expression(entry)))
          } :+ ref()
        }
      case "PatternAssignment" | "PatternVariableDeclaration" =>
        patternScope(syntax) {
          savedSequence(syntax, expression(child(syntax, "expression")))(ref =>
            Seq(
              control(
                syntax,
                ControlStructureTypes.IF,
                operator(syntax, Operators.logicalNot, Seq(pattern(child(syntax, "pattern"), ref))),
                Ast(
                  located(
                    NewControlStructure().controlStructureType(ControlStructureTypes.THROW).code("<pattern mismatch>"),
                    syntax
                  )
                )
              ),
              ref()
            )
          )
        }
      case "SwitchExpression" =>
        patternScope(syntax) {
          savedSequence(syntax, literal(syntax, "null", tpe(syntax))) { result =>
            val matched = saved(syntax, expression(child(syntax, "expression"))) { ref =>
              def cases(remaining: List[Value]): Ast = remaining match {
                case head :: tail =>
                  val cond = guard(child(head, "guard"), ref)
                  control(
                    head,
                    ControlStructureTypes.IF,
                    cond,
                    block(
                      head,
                      Seq(operator(head, Operators.assignment, Seq(result(), expression(child(head, "expression")))))
                    ),
                    Some(cases(tail))
                  )
                case Nil =>
                  Ast(
                    located(
                      NewControlStructure()
                        .controlStructureType(ControlStructureTypes.THROW)
                        .code("<non-exhaustive switch>"),
                      syntax
                    )
                  )
              }
              cases(children(syntax, "case").toList)
            }
            Seq(matched, result())
          }
        }
      case "AssertStatement" | "AssertInitializer" =>
        val failure = Ast(
          located(
            NewControlStructure()
              .controlStructureType(ControlStructureTypes.THROW)
              .code("<assertion failure>"),
            syntax
          )
        ).withChildren(children(syntax, "message").map(expression))
        block(
          syntax,
          Seq(
            control(
              syntax,
              ControlStructureTypes.IF,
              operator(syntax, "<operator>.assertionsEnabled", Nil),
              control(
                syntax,
                ControlStructureTypes.IF,
                operator(syntax, Operators.logicalNot, Seq(expression(child(syntax, "condition")))),
                failure
              )
            )
          )
        )
      case "AwaitExpression" => operator(syntax, "<operator>.await", Seq(expression(child(syntax, "expression"))))
      case "YieldStatement"  =>
        operator(
          syntax,
          if (bool(syntax, "star")) "<operator>.yieldAll" else "<operator>.yield",
          Seq(expression(child(syntax, "expression")))
        )
      case "SimpleIdentifier" => reference(syntax, string(syntax, "reference"), string(syntax, "name"), None)
      case "ThisExpression" | "SuperExpression" => thisAst(syntax)
      case "StringLiteral" | "SymbolLiteral" | "IntegerLiteral" | "DoubleLiteral" | "BooleanLiteral" | "NullLiteral" =>
        literal(syntax, code(syntax), tpe(syntax))
      case "NamedExpression" =>
        val ast = expression(child(syntax, "expression"))
        ast.root.collect { case entryNode: ExpressionNew => entryNode.argumentName = Some(string(syntax, "name")) }
        ast
      case "ParenthesizedExpression" | "FunctionReference" =>
        expression(child(syntax, "expression"))
      case "InterpolationExpression" => interpolatedValue(syntax, expression(child(syntax, "expression")))
      case "MethodInvocation"        =>
        val originalTarget = string(syntax, "target")
        val targetId       = functionValues.getOrElse(originalTarget, originalTarget)
        val name           = string(child(syntax, "name"), "name")
        val target         = sym(targetId)
        val functionValue  = Set("LOCAL_VARIABLE", "PARAMETER").contains(string(sym(originalTarget), "kind"))
        val receiver       =
          (if (functionValue) Some(identifier(syntax, name, originalTarget, tpe(sym(originalTarget)))) else None)
            .orElse(children(syntax, "receiver").headOption.map(expression))
            .orElse(cascadeBase(syntax))
            .orElse(if (string(target, "kind") == "METHOD" && !bool(target, "static")) Some(thisAst(syntax)) else None)
        if (functionValue && string(target, "kind") == "CONSTRUCTOR")
          newInstance(syntax, targetId, name, Some(child(syntax, "arguments")))
        else if (nullAware(syntax) && receiver.nonEmpty)
          guarded(syntax, receiver.get)(ref =>
            call(
              syntax,
              targetId,
              name,
              Some(child(syntax, "arguments")),
              Some(ref()),
              callableReceiver = functionValue
            )
          )
        else
          call(
            syntax,
            if (functionValue && targetId == originalTarget) "" else targetId,
            name,
            Some(child(syntax, "arguments")),
            if (bool(target, "static") && !functionValue) None else receiver,
            callableReceiver = functionValue
          )
      case "EnumConstantDeclaration" =>
        val constructorName = string(symbol(syntax, "target"), "name", "new")
        newInstance(
          syntax,
          string(syntax, "target"),
          if (constructorName == "new") "<init>" else constructorName,
          children(syntax, "arguments").headOption,
          receiver =>
            Seq(
              syntheticAssignment(
                syntax,
                enumField(syntax, receiver(), "index", "int"),
                literal(syntax, syntax("ordinal").num.toInt.toString, "int")
              ),
              syntheticAssignment(
                syntax,
                enumField(syntax, receiver(), "<enumName>", "String"),
                literal(syntax, ujson.write(string(syntax, "name")), "String")
              )
            )
        )
      case "InstanceCreationExpression" =>
        newInstance(syntax, string(syntax, "target"), string(syntax, "name"), Some(child(syntax, "arguments")))
      case "ConstructorInvocation" =>
        call(
          syntax,
          string(syntax, "target"),
          string(syntax, "name"),
          Some(child(syntax, "arguments")),
          Some(thisAst(syntax))
        )
      case "ExtensionOverride"     => expression(children(child(syntax, "arguments"), "argument").head)
      case "ImplicitCallReference" =>
        boundMethodRef(syntax, string(syntax, "target"), expression(child(syntax, "expression")))
      case "TypeLiteral" =>
        Ast(located(NewTypeRef().code(code(syntax)).typeFullName(string(syntax, "referencedType", "ANY")), syntax))
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
        if (string(sym(targetId), "kind") == "CONSTRUCTOR")
          newInstance(syntax, targetId, string(receiver, "name", "<init>"), Some(child(syntax, "arguments")))
        else
          call(
            syntax,
            targetId,
            string(receiver, "name", "<invoke>"),
            Some(child(syntax, "arguments")),
            Some(value),
            callableReceiver = true
          )
      case "PropertyAccess" | "PrefixedIdentifier" =>
        val receiver =
          children(syntax, "receiver").headOption
            .map(expression)
            .orElse(cascadeBase(syntax))
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
            .orElse(cascadeBase(syntax))
            .getOrElse(thisAst(syntax))
        if (nullAware(syntax))
          guarded(syntax, base)(ref =>
            resolvedOperator(
              syntax,
              Operators.indexAccess,
              Seq(ref(), expression(child(syntax, "index"))),
              string(syntax, "operatorTarget")
            )
          )
        else
          resolvedOperator(
            syntax,
            Operators.indexAccess,
            Seq(base, expression(child(syntax, "index"))),
            string(syntax, "operatorTarget")
          )
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
              resolvedOperator(
                syntax,
                binaryOperators.getOrElse(op.dropRight(1), "<operator>.unknown"),
                Seq(read(), expression(right)),
                string(syntax, "operatorTarget")
              )
            )
        }
        update(syntax, left)(assign)
      case "BinaryExpression" =>
        if (string(syntax, "operator") == "??")
          saved(syntax, expression(child(syntax, "left")))(ref =>
            operator(
              syntax,
              Operators.conditional,
              Seq(nonNull(syntax, ref()), ref(), expression(child(syntax, "right")))
            )
          )
        else {
          val targetId = string(syntax, "operatorTarget")
          resolvedOperator(
            syntax,
            binaryOperators.getOrElse(string(syntax, "operator"), "<operator>.unknown"),
            Seq(expression(child(syntax, "left")), expression(child(syntax, "right"))),
            targetId
          )
        }
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
        val operand  = child(syntax, "operand")
        val readId   = string(syntax, "read", string(operand, "reference"))
        val writeId  = string(syntax, "write", string(operand, "reference"))
        val accessor = Seq(readId, writeId).exists(id =>
          Set("GETTER", "SETTER").contains(string(sym(id), "kind")) && !bool(sym(id), "synthetic")
        )
        if (
          Set("++", "--").contains(string(syntax, "operator")) &&
          (accessor || lateLocals.contains(readId) || operatorDispatch(
            string(syntax, "operatorTarget"),
            tpe(operand)
          ) || string(operand, "kind") == "IndexExpression")
        ) {
          update(syntax, operand) { (read, write) =>
            saved(syntax, read()) { before =>
              saved(
                syntax,
                resolvedOperator(
                  syntax,
                  if (string(syntax, "operator") == "++") Operators.addition else Operators.subtraction,
                  Seq(before(), literal(syntax, "1", "int")),
                  string(syntax, "operatorTarget")
                )
              ) { after =>
                block(syntax, Seq(write(after()), if (prefix) after() else before()))
              }
            }
          }
        } else {
          val value = if (readId.nonEmpty && string(operand, "kind") != "IndexExpression") {
            val receiver = children(operand, "receiver").headOption.map(expression).orElse(cascadeBase(operand))
            reference(operand, readId, string(operand, "name"), receiver)
          } else expression(operand)
          resolvedOperator(syntax, name, Seq(value), string(syntax, "operatorTarget"))
        }
      case "StringInterpolation" | "AdjacentStrings" => stringAssembly(syntax)
      case "ListLiteral" | "SetOrMapLiteral"         => collection(syntax)
      case "MapLiteralEntry"                         =>
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
      case "FunctionExpression" => closure(syntax, syntax)
      case "ThrowExpression"    => throwAst(syntax, children(syntax, "expression").map(expression))
      case "RethrowExpression"  => throwAst(syntax, caughtValues.toSeq.flatMap(_()))
      case _                    => unknown(syntax)
    }
    def throwAst(syntax: Value, values: Seq[Ast]): Ast =
      args(
        located(NewControlStructure().controlStructureType(ControlStructureTypes.THROW).code(code(syntax)), syntax),
        values
      )

    def catchDispatch(syntax: Value, clauses: Seq[Value]): Ast = {
      def caught(name: String, typ: String): Ast = {
        val value = operator(syntax, s"<operator>.$name", Nil)
        value.root.collect { case call: NewCall =>
          call.code = s"<$name@${syntax("id").num.toInt}>"
          call.typeFullName = typ
        }
        value
      }
      val dispatch = savedSequence(syntax, caught("caughtException", "Object")) { exception =>
        Seq(savedSequence(syntax, caught("caughtStackTrace", "StackTrace")) { stack =>
          def choose(remaining: Seq[Value]): Ast = remaining.headOption match {
            case None =>
              val rethrow = throwAst(syntax, Seq(exception(), stack()))
              rethrow.root.collect { case control: NewControlStructure => control.code = "<unmatched catch: rethrow>" }
              rethrow
            case Some(clause) =>
              val previousDeclarations = declarations.toMap
              val previousCaught       = caughtValues
              caughtValues = Some(() => Seq(exception(), stack()))
              def bind(role: String, value: () => Ast): Seq[Ast] = children(clause, role).flatMap { parameter =>
                val local      = statements(parameter)
                val assignment = syntheticAssignment(
                  parameter,
                  identifier(
                    parameter,
                    string(parameter, "name"),
                    string(parameter, "declaration"),
                    tpe(symbol(parameter))
                  ),
                  value()
                )
                local :+ assignment
              }
              val body =
                block(clause, bind("exception", exception) ++ bind("stack", stack) ++ statements(child(clause, "body")))
              caughtValues = previousCaught
              declarations.clear(); declarations ++= previousDeclarations
              children(clause, "type").headOption match {
                case Some(typ) =>
                  val condition = operator(
                    clause,
                    Operators.instanceOf,
                    Seq(exception(), Ast(NewTypeRef().code(code(typ)).typeFullName(tpe(typ))))
                  )
                  condition.root.collect { case call: NewCall => call.code = s"<caught exception> is ${code(typ)}" }
                  control(clause, ControlStructureTypes.IF, condition, body, Some(choose(remaining.tail)))
                case None if remaining.tail.nonEmpty =>
                  control(
                    clause,
                    ControlStructureTypes.IF,
                    literal(clause, "true", "bool"),
                    body,
                    Some(choose(remaining.tail))
                  )
                case None => body
              }
          }
          Seq(choose(clauses))
        })
      }
      val handler =
        located(NewControlStructure().controlStructureType(ControlStructureTypes.CATCH).code(code(syntax)), syntax)
      val condition = literal(syntax, "true", "bool")
      Ast(handler).withChild(condition).withConditionEdge(handler, condition.root.get).withChild(dispatch)
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
    def jumpTarget(syntax: Value, name: String): Ast =
      Ast(located(NewJumpTarget().name(name).code(name).parserTypeName("Label"), syntax))
    def loopBody(syntax: Value): Seq[Ast] =
      collectionBodies.get(syntax("id").num.toInt).map(_()).getOrElse(statements(child(syntax, "body"))) ++
        continueTargets.get(syntax("id").num.toInt).map(jumpTarget(syntax, _))
    def withSwitchLabels(syntax: Value)(body: => Seq[Ast]): Seq[Ast] = {
      val previous = labelTargets.toMap
      children(syntax, "member").foreach { member =>
        strings(member, "labels").foreach { label =>
          val target = s"<switch-label>${member("offset").num.toInt}:$label"
          labelTargets(label) = (target, target)
        }
      }
      try body
      finally { labelTargets.clear(); labelTargets ++= previous }
    }
    def caseLabels(syntax: Value): Seq[Ast] = strings(syntax, "labels").map { label =>
      jumpTarget(syntax, labelTargets(label)._2)
    }
    def statements(syntax: Value): Seq[Ast] = string(syntax, "kind") match {
      case "Block"                               => children(syntax, "statement").flatMap(statements)
      case "PatternVariableDeclarationStatement" => Seq(expression(child(syntax, "declaration")))
      case "VariableDeclarationStatement"        => statements(child(syntax, "variables"))
      case "VariableDeclarationList"             => children(syntax, "variable").flatMap(statements)
      case "VariableDeclaration" | "DeclaredIdentifier" | "CatchClauseParameter" =>
        val name  = string(syntax, "name")
        val typ   = tpe(symbol(syntax))
        val local = located(
          NewLocal().name(name).code(name).typeFullName(typ).genericSignature(string(symbol(syntax), "type", "ANY")),
          syntax
        )
        declarations(string(syntax, "declaration")) = local
        val id = string(syntax, "declaration")
        if (bool(symbol(syntax), "late")) {
          lateLocals(id) = None
          Seq(Ast(local)) ++ children(syntax, "initializer").flatMap(init => lateLocalInitializer(syntax, init, id))
        } else
          Seq(Ast(local)) ++ children(syntax, "initializer").map { init =>
            val rhs = expression(init)
            if (bool(symbol(syntax), "final"))
              rhs.nodes
                .collect { case ref: NewMethodRef => ref }
                .lastOption
                .foreach(ref => functionValues(string(syntax, "declaration")) = ref.methodFullName)
            operator(
              syntax,
              Operators.assignment,
              Seq(identifier(syntax, name, string(syntax, "declaration"), typ), rhs)
            )
          }
      case "ExpressionStatement" => Seq(expression(child(syntax, "expression")))
      case "ReturnStatement"     =>
        val values = children(syntax, "expression").map(expression)
        Seq(
          args(
            located(NewReturn().code(code(syntax)), syntax),
            if (values.isEmpty && returnsInstance) Seq(thisAst(syntax)) else values
          )
        )
      case "IfStatement" =>
        Seq(
          control(
            syntax,
            ControlStructureTypes.IF,
            condition(syntax),
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
            block(syntax, loopBody(syntax))
          )
        )
      case "ForStatement" | "ForElement" =>
        val parts = child(syntax, "parts")
        if (string(parts, "kind") == "ForEachParts") {
          val variable    = children(parts, "variable").headOption.getOrElse(child(parts, "pattern"))
          val declaration = if (string(variable, "kind") == "DeclaredIdentifier") statements(variable) else Nil
          val name        = string(variable, "name")
          val loop        =
            saved(
              syntax,
              operator(
                syntax,
                if (bool(syntax, "await")) "<operator>.streamIterator" else "<operator>.iterator",
                Seq(expression(child(parts, "iterable")))
              )
            )(ref => {
              val assign =
                if (children(parts, "pattern").nonEmpty)
                  patternScope(syntax) { pattern(variable, () => field(syntax, ref(), "current")) }
                else
                  operator(
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
                if (bool(syntax, "await"))
                  operator(
                    syntax,
                    "<operator>.await",
                    Seq(call(syntax, "<unresolved>.moveNext", "moveNext", None, Some(ref())))
                  )
                else call(syntax, "<unresolved>.moveNext", "moveNext", None, Some(ref())),
                block(syntax, Seq(assign) ++ loopBody(syntax))
              )
            })
          declaration :+ loop
        } else {
          val out =
            located(NewControlStructure().controlStructureType(ControlStructureTypes.FOR).code(code(syntax)), syntax)
          val init = block(syntax, children(parts, "init").flatMap(statements))
          val cond = children(parts, "condition").headOption.map(expression).getOrElse(literal(syntax, "true", "bool"))
          val update = block(syntax, children(parts, "update").map(expression))
          val body   = block(syntax, loopBody(syntax))
          Seq(
            Ast(out)
              .withChildren(Seq(init, cond, update, body))
              .withForInitEdge(out, init.root.get)
              .withConditionEdge(out, cond.root.get)
              .withForUpdateEdge(out, update.root.get)
              .withForBodyEdge(out, body.root.get)
          )
        }
      case "LabeledStatement" =>
        val previous = labelTargets.toMap
        temporary += 1
        val end         = s"<break>$temporary"
        val next        = s"<continue>$temporary"
        val statement   = child(syntax, "statement")
        val statementId = statement("id").num.toInt
        strings(syntax, "labels").foreach(label => labelTargets(label) = (end, next))
        continueTargets(statementId) = next
        val body = statements(statement)
        continueTargets.remove(statementId)
        labelTargets.clear(); labelTargets ++= previous
        body :+ jumpTarget(syntax, end)
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
        val labels = children(syntax, "label").flatMap { label =>
          labelTargets.get(string(label, "name")).map { case (end, next) =>
            val target = if (string(syntax, "kind") == "BreakStatement") end else next
            Ast(NewJumpLabel().name(target).code(code(label)))
          }
        }
        Seq(Ast(out).withChildren(labels))
      case "SwitchStatement"
          if children(syntax, "member").exists(memberSyntax => string(memberSyntax, "kind") == "SwitchPatternCase") =>
        withSwitchLabels(syntax) {
          Seq(patternScope(syntax) {
            saved(syntax, expression(child(syntax, "condition"))) { ref =>
              def cases(remaining: List[Value]): Ast = remaining match {
                case head :: _ =>
                  val (empty, rest)                    = remaining.span(children(_, "statement").isEmpty)
                  val group                            = empty ++ rest.headOption
                  val tail                             = rest.drop(1)
                  def joins(member: Value): Seq[Value] = member.obj.get("joins").toSeq.flatMap(_.arr)
                  val locals                           =
                    group.flatMap(joins).map(string(_, "target")).distinct.filterNot(declarations.contains).map { id =>
                      val local = located(
                        NewLocal()
                          .name(string(sym(id), "name"))
                          .code(string(sym(id), "name"))
                          .typeFullName(tpe(sym(id))),
                        head
                      )
                      declarations(id) = local
                      Ast(local)
                    }
                  val conditions = group.map { member =>
                    val matched = string(member, "kind") match {
                      case "SwitchDefault"     => literal(member, "true", "bool")
                      case "SwitchPatternCase" => guard(child(member, "guard"), ref)
                      case _ => operator(member, Operators.equals, Seq(ref(), expression(child(member, "expression"))))
                    }
                    val copies = joins(member).map { binding =>
                      val source = string(binding, "source")
                      val target = string(binding, "target")
                      operator(
                        member,
                        Operators.assignment,
                        Seq(
                          identifier(member, string(sym(target), "name"), target, tpe(sym(target))),
                          identifier(member, string(sym(source), "name"), source, tpe(sym(source)))
                        )
                      )
                    }
                    if (copies.isEmpty) matched
                    else
                      operator(
                        member,
                        Operators.logicalAnd,
                        Seq(matched, block(member, copies :+ literal(member, "true", "bool")))
                      )
                  }
                  val cond =
                    conditions.reduceLeft((left, right) => operator(head, Operators.logicalOr, Seq(left, right)))
                  control(
                    head,
                    ControlStructureTypes.IF,
                    if (locals.isEmpty) cond else block(head, locals :+ cond),
                    block(
                      head,
                      group.flatMap(caseLabels) ++ group.flatMap(children(_, "statement")).flatMap(statements)
                    ),
                    if (tail.nonEmpty) Some(cases(tail)) else None
                  )
                case Nil => block(syntax, Nil)
              }
              // Keep a switch boundary for explicit break statements.
              control(
                syntax,
                ControlStructureTypes.SWITCH,
                ref(),
                block(
                  syntax,
                  Seq(
                    Ast(NewJumpTarget().name("default").code("default").parserTypeName("SwitchDefault")),
                    cases(children(syntax, "member").toList)
                  )
                )
              )
            }
          })
        }
      case "SwitchStatement" =>
        withSwitchLabels(syntax) {
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
            ) ++ children(clauseNode, "expression").map(expression) ++ caseLabels(clauseNode) ++ children(
              clauseNode,
              "statement"
            ).flatMap(
              statements
            ) ++ (if (children(clauseNode, "statement").nonEmpty)
                    Seq(
                      Ast(
                        NewControlStructure().controlStructureType(ControlStructureTypes.BREAK).code("<implicit break>")
                      )
                    )
                  else Nil)
          }
          Seq(
            control(syntax, ControlStructureTypes.SWITCH, expression(child(syntax, "condition")), block(syntax, cases))
          )
        }
      case "TryStatement" =>
        val out =
          located(NewControlStructure().controlStructureType(ControlStructureTypes.TRY).code(code(syntax)), syntax)
        val body    = block(syntax, statements(child(syntax, "body")))
        var ast     = Ast(out).withChild(body).withTryBodyEdge(out, body.root.get)
        val clauses = children(syntax, "catch")
        if (clauses.nonEmpty) {
          val dispatcher = catchDispatch(syntax, clauses)
          ast = ast.withChild(dispatcher).withCatchBodyEdge(out, dispatcher.root.get)
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
      val sourceId = string(syntax, "declaration", s"$filename#${syntax("offset").num.toInt}:closure")
      // Field initializers are expanded in each constructor and capture different receiver parameters.
      val id = if (initializerContext.isEmpty) sourceId else s"$sourceId:initializer:$initializerContext"
      boundTargets(id) = sourceId
      capturedClosure(syntax, function, id)(prefix => method(syntax, function, Some(id), prefix))
    }
    def capturedClosure(syntax: Value, function: Value, id: String)(build: Seq[Ast] => Ast): Ast = {
      val outer    = declarations.toMap
      val captured = mutable.ArrayBuffer.empty[Ast]
      val ref      = located(NewMethodRef().code(code(syntax)).methodFullName(id).typeFullName(tpe(function)), syntax)
      var refAst   = Ast(ref)
      // Capture only referenced lexical declarations, never members or unrelated locals.
      def references(valueSyntax: Value): Set[String] = {
        val referenced = Set("reference", "read", "write", "target").map(string(valueSyntax, _)).filter(_.nonEmpty)
        val needsThis  = Set("ThisExpression", "SuperExpression").contains(string(valueSyntax, "kind")) ||
          referenced.exists(id =>
            Set("FIELD", "GETTER", "SETTER", "METHOD").contains(string(sym(id), "kind")) && !bool(sym(id), "static")
          )
        referenced ++ referenced.flatMap(id => lateLocals.get(id).flatten) ++
          (if (needsThis) Set("this") else Set.empty[String]) ++
          valueSyntax("children").arr.flatMap(clauseNode => references(nodes(clauseNode("node").num.toInt)))
      }
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
      extraMethods += build(captured.toSeq)
      owner = previousOwner
      ownerType = previousOwnerType
      declarations.clear(); declarations ++= outer
      refAst
    }
    def initializerExpression(syntax: Value, constructor: String): Ast = {
      val previous          = initializerContext
      val previousFunctions = functionValues.toMap
      initializerContext = constructor
      def register(node: Value): Unit = {
        if (Set("FunctionExpression", "FunctionDeclaration").contains(string(node, "kind"))) {
          val sourceId = string(node, "declaration")
          if (sourceId.nonEmpty) functionValues(sourceId) = s"$sourceId:initializer:$constructor"
        }
        node("children").arr.foreach(entry => register(nodes(entry("node").num.toInt)))
      }
      register(syntax)
      val ast = expression(syntax)
      initializerContext = previous
      functionValues.clear(); functionValues ++= previousFunctions
      ast
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
      val outer          = declarations.toMap
      val previousCaught = caughtValues
      caughtValues = None
      val id = forcedId.getOrElse(
        string(syntax, "declaration", s"$filename#${syntax("offset").num.toInt}:${string(syntax, "name")}")
      )
      val target                  = sym(boundTargets.getOrElse(id, id))
      val constructor             = string(syntax, "kind") == "ConstructorDeclaration"
      val previousReturnsInstance = returnsInstance
      returnsInstance = constructor && !bool(syntax, "factory")
      val instance = ownerType == "TYPE_DECL" && !bool(syntax, "static") && !bool(syntax, "factory")
      val receiver = if (instance) {
        val out = NewMethodParameterIn()
          .name("this")
          .code("this")
          .index(0)
          .order(0)
          .typeFullName(string(sym(currentType), "extendedType", currentType))
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
          instanceFields
            .filterNot(fieldSyntax => lazyMembers.contains(string(fieldSyntax, "declaration")))
            .flatMap(fieldSyntax =>
              children(fieldSyntax, "initializer").map(init =>
                operator(
                  fieldSyntax,
                  Operators.assignment,
                  Seq(
                    field(fieldSyntax, thisAst(fieldSyntax), string(fieldSyntax, "name")),
                    initializerExpression(init, id)
                  )
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
        val invocation =
          if (bool(sym(targetId), "factory"))
            args(out, forwarded.map(_._1), forwarded.map(_._2))
          else
            savedSequence(redirect, operator(redirect, Operators.alloc, Nil)) { receiver =>
              val base = receiver()
              Seq(
                args(out, base +: forwarded.map(_._1), 0 +: forwarded.map(_._2))
                  .withReceiverEdge(out, base.root.get),
                receiver()
              )
            }
        args(located(NewReturn().code(code(redirect)), redirect), Seq(invocation))
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
          .genericSignature(string(target, "genericSignature"))
          .astParentType(ownerType)
          .astParentFullName(owner),
        syntax
      )
      val execution = body.toSeq.flatMap(bodySyntax =>
        (if (bool(bodySyntax, "async")) Seq("async") else Nil) ++ (if (bool(bodySyntax, "generator")) Seq("generator")
                                                                   else Nil)
      )
      val annotations = execution.map(name => Ast(NewAnnotation().name(name).fullName(s"dart.$name").code(name)))
      val modifiers   = Seq(if (bool(target, "private")) ModifierTypes.PRIVATE else ModifierTypes.PUBLIC) ++
        (if (constructor) Seq(ModifierTypes.CONSTRUCTOR) else Nil) ++ (if (!instance) Seq(ModifierTypes.STATIC)
                                                                       else Nil)
      val result =
        if (constructor && !bool(syntax, "factory"))
          Seq(args(NewReturn().code("return this"), Seq(thisAst(syntax))))
        else Nil
      val ast = Ast(out)
        .withChildren(receiver ++ parameters)
        .withChildren(typeParameters(function, "METHOD", id))
        .withChildren(signatureDeclarations(string(syntax, "declaration", id), "METHOD", id))
        .withChild(block(body.getOrElse(syntax), prefix ++ initializers ++ superCalls ++ bodyAsts ++ result))
        .withChild(
          Ast(
            NewMethodReturn()
              .typeFullName(string(target, "returnTypeId", string(target, "returnType", "ANY")))
              .evaluationStrategy(EvaluationStrategies.BY_VALUE)
              .code("RET")
          )
        )
        .withChildren(modifiers.map(memberSyntax => Ast(NewModifier().modifierType(memberSyntax))))
        .withChildren(annotations)
      returnsInstance = previousReturnsInstance
      caughtValues = previousCaught
      declarations.clear(); declarations ++= outer
      ast
    }
    def enumField(syntax: Value, receiver: Ast, name: String, typ: String): Ast = {
      val result = field(syntax, receiver, name)
      val base   = receiver.root.collect { case node: NewIdentifier => node.name }.getOrElse("this")
      result.root.collect { case node: NewCall => node.code = s"$base.$name"; node.typeFullName = typ }
      result
    }
    def syntheticAssignment(syntax: Value, left: Ast, right: Ast): Ast = {
      val result                 = operator(syntax, Operators.assignment, Seq(left, right))
      def text(ast: Ast): String = ast.root.collect { case node: ExpressionNew => node.code }.getOrElse("")
      result.root.collect { case node: NewCall =>
        node.code = s"${text(left)} = ${text(right)}"
        node.typeFullName = astType(right)
      }
      result
    }
    def enumMembers(syntax: Value): Seq[Ast] = {
      val valuesId = string(syntax, "values")
      val storage  =
        Seq(("values", tpe(sym(valuesId)), true), ("index", "int", false), ("<enumName>", "String", false)).map {
          case (name, typ, static) =>
            val member = NewMember().name(name).code(name).typeFullName(typ)
            if (static) declarations(valuesId) = member
            Ast(member).withChildren(
              (Seq(ModifierTypes.FINAL) ++
                (if (static) Seq(ModifierTypes.STATIC) else Nil) ++
                Seq(if (name == "<enumName>") ModifierTypes.PRIVATE else ModifierTypes.PUBLIC))
                .map(modifier => Ast(NewModifier().modifierType(modifier)))
            )
        }
      val accessors = Seq("index", "name", "toString").map { name =>
        val previous = declarations.toMap
        val receiver = NewMethodParameterIn()
          .name("this")
          .code("this")
          .index(0)
          .order(0)
          .typeFullName(currentType)
          .evaluationStrategy(EvaluationStrategies.BY_REFERENCE)
          .isVariadic(false)
        declarations("this") = receiver
        val typ    = if (name == "index") "int" else "String"
        val value  = enumField(syntax, thisAst(syntax), if (name == "index") "index" else "<enumName>", typ)
        val result =
          if (name == "toString")
            operator(
              syntax,
              Operators.addition,
              Seq(literal(syntax, ujson.write(string(syntax, "name") + "."), "String"), value)
            )
          else value
        result.root.collect { case node: NewCall => node.typeFullName = typ }
        // A shared name/signature would make dynamic linking select this helper over a source override.
        val method = NewMethod()
          .name(s"<enum:$name>")
          .fullName(s"$currentType:<enum:$name>")
          .code(s"<generated enum $name>")
          .filename(filename)
          .isExternal(false)
          .signature(s"$typ(0)")
          .astParentType("TYPE_DECL")
          .astParentFullName(currentType)
        val body = block(syntax, Seq(args(NewReturn().code(s"return enum $name"), Seq(result))))
        val ast  = Ast(method)
          .withChild(Ast(receiver))
          .withChild(body)
          .withChild(
            Ast(NewMethodReturn().code("RET").typeFullName(typ).evaluationStrategy(EvaluationStrategies.BY_VALUE))
          )
          .withChild(
            Ast(NewAnnotation().name("dart.generated.enum").fullName("dart.generated.enum").code("dart.generated.enum"))
          )
        declarations.clear(); declarations ++= previous
        ast
      }
      storage ++ accessors
    }
    def member(syntax: Value): Ast = {
      val out =
        located(
          NewMember()
            .name(string(syntax, "name"))
            .code(code(syntax))
            .typeFullName(tpe(symbol(syntax)))
            .genericSignature(string(symbol(syntax), "type", "ANY")),
          syntax
        )
      declarations(string(syntax, "declaration")) = out
      Ast(out).withChildren(
        (Seq(
          if (bool(symbol(syntax), "private")) ModifierTypes.PRIVATE else ModifierTypes.PUBLIC
        ) ++ (if (bool(symbol(syntax), "static")) Seq(ModifierTypes.STATIC) else Nil) ++
          (if (bool(symbol(syntax), "final")) Seq(ModifierTypes.FINAL) else Nil)).map(memberSyntax =>
          Ast(NewModifier().modifierType(memberSyntax))
        )
      )
    }
    def lazyAccessors(syntax: Value): Seq[Ast] = {
      val variable = symbol(syntax)
      val instance = !bool(variable, "static")
      val name     = string(syntax, "name")
      val outer    = declarations.toMap
      Seq("getter", "setter").flatMap { kind =>
        val id = string(variable, kind)
        if (id.isEmpty) Nil
        else {
          val parameters = (if (instance) Seq("this" -> currentType) else Nil) ++
            (if (kind == "setter") Seq("value" -> tpe(variable)) else Nil)
          val inputs = parameters.zipWithIndex.map { case ((paramName, typ), index) =>
            val param = NewMethodParameterIn()
              .name(paramName)
              .code(paramName)
              .index(index + (if (instance) 0 else 1))
              .order(index + (if (instance) 0 else 1))
              .typeFullName(typ)
              .evaluationStrategy(
                if (paramName == "this") EvaluationStrategies.BY_REFERENCE
                else EvaluationStrategies.BY_VALUE
              )
              .isVariadic(false)
            declarations(paramName) = param
            Ast(param)
          }
          def location(): Ast =
            field(syntax, if (instance) thisAst(syntax) else identifier(syntax, owner, "", owner), name)
          def initialized(): Ast = operator(syntax, "<operator>.isInitialized", Seq(location()))
          def fail(): Ast        = lateFailure(syntax, name)
          val guard              = if (kind == "getter") {
            Seq(
              initializeIfNeeded(
                syntax,
                name,
                () => location(),
                children(syntax, "initializer").headOption.map(expression),
                bool(variable, "final")
              ),
              args(NewReturn().code(s"return $name"), Seq(location()))
            )
          } else {
            (if (bool(variable, "final"))
               Seq(control(syntax, ControlStructureTypes.IF, initialized(), block(syntax, Seq(fail()))))
             else Nil) :+
              operator(
                syntax,
                Operators.assignment,
                Seq(location(), identifier(syntax, "value", "value", tpe(variable)))
              )
          }
          val out = located(
            NewMethod()
              .name(name)
              .fullName(id)
              .code(code(syntax))
              .filename(filename)
              .isExternal(false)
              .signature(s"${if (kind == "getter") tpe(variable) else "void"}(${if (kind == "getter") 0 else 1})")
              .astParentType(ownerType)
              .astParentFullName(owner),
            syntax
          )
          val ast = Ast(out)
            .withChildren(inputs)
            .withChild(block(syntax, guard))
            .withChild(
              Ast(
                NewMethodReturn()
                  .typeFullName(if (kind == "getter") tpe(variable) else "void")
                  .code("RET")
                  .evaluationStrategy(EvaluationStrategies.BY_VALUE)
              )
            )
            .withChild({
              val annotation = if (bool(variable, "late")) "late" else "lazy"
              Ast(NewAnnotation().name(annotation).fullName(s"dart.$annotation").code(annotation))
            })
            .withChildren(if (instance) Nil else Seq(Ast(NewModifier().modifierType(ModifierTypes.STATIC))))
          declarations.clear(); declarations ++= outer
          Seq(ast)
        }
      }
    }
    def initializerMethod(
      syntax: Value,
      fields: Seq[Value],
      id: String,
      name: String,
      instance: Boolean,
      forwarding: Boolean = false
    ): Ast = {
      val old        = declarations.toMap
      val parameters = if (instance) {
        val receiver = NewMethodParameterIn()
          .name("this")
          .code("this")
          .index(0)
          .order(0)
          .typeFullName(string(sym(currentType), "extendedType", currentType))
          .evaluationStrategy(EvaluationStrategies.BY_REFERENCE)
          .isVariadic(false)
        declarations("this") = receiver
        Seq(Ast(receiver))
      } else Nil
      val forwarded = if (forwarding) strings(sym(id), "parameters").zipWithIndex.map { case (parameterId, index) =>
        val parameter = sym(parameterId)
        val node      = NewMethodParameterIn()
          .name(string(parameter, "name"))
          .code(string(parameter, "name"))
          .index(index + 1)
          .order(index + 1)
          .typeFullName(tpe(parameter))
          .evaluationStrategy(EvaluationStrategies.BY_VALUE)
          .isVariadic(false)
        declarations(parameterId) = node
        Ast(node)
      }
      else Nil
      val superParameters = strings(sym(string(sym(id), "superConstructor")), "parameters")
      val superFormals = if (forwarding) strings(sym(id), "parameters").zipWithIndex.map { case (parameterId, index) =>
        val parameter = ujson.Obj.from(syntax.obj)
        parameter("kind") = "SuperFormalParameter"
        parameter("name") = string(sym(parameterId), "name")
        parameter("declaration") = parameterId
        parameter("superParameter") = superParameters.lift(index).getOrElse("")
        parameter
      }
      else Nil
      val values = fields
        .filterNot(fieldSyntax => lazyMembers.contains(string(fieldSyntax, "declaration")))
        .flatMap(fieldSyntax =>
          (if (string(fieldSyntax, "kind") == "EnumConstantDeclaration") Seq(fieldSyntax)
           else children(fieldSyntax, "initializer")).map(init =>
            operator(
              fieldSyntax,
              Operators.assignment,
              Seq(
                field(
                  fieldSyntax,
                  if (instance) thisAst(fieldSyntax) else identifier(fieldSyntax, owner, "", owner),
                  string(fieldSyntax, "name")
                ),
                initializerExpression(init, id)
              )
            )
          )
        )
      val enumeration = if (!instance && string(syntax, "kind") == "EnumDeclaration") {
        val constants = children(syntax, "constant").map { constant =>
          reference(constant, string(constant, "declaration"), string(constant, "name"), None)
        }
        val typ    = tpe(sym(string(syntax, "values")))
        val values = operator(syntax, Operators.arrayInitializer, constants)
        values.root.collect { case node: NewCall =>
          node.typeFullName = typ
          node.code = children(syntax, "constant").map(string(_, "name")).mkString("const [", ", ", "]")
        }
        Seq(syntheticAssignment(syntax, enumField(syntax, identifier(syntax, owner, "", owner), "values", typ), values))
      } else Nil
      val out = NewMethod()
        .name(name)
        .fullName(id)
        .code(name)
        .filename(filename)
        .isExternal(false)
        .signature(s"${if (instance) currentType else "void"}(${forwarded.size})")
        .astParentType(ownerType)
        .astParentFullName(owner)
      val ast = Ast(out)
        .withChildren(parameters ++ forwarded)
        .withChild(
          block(
            syntax,
            values ++ enumeration ++ (if (instance)
                                        implicitSuper(syntax, id, superFormals) :+ args(
                                          NewReturn().code("return this"),
                                          Seq(thisAst(syntax))
                                        )
                                      else Nil)
          )
        )
        .withChild(
          Ast(
            NewMethodReturn()
              .typeFullName(if (instance) currentType else "void")
              .code("RET")
              .evaluationStrategy(EvaluationStrategies.BY_VALUE)
          )
        )
        .withChild(Ast(NewModifier().modifierType(if (instance) ModifierTypes.CONSTRUCTOR else ModifierTypes.STATIC)))
      declarations.clear(); declarations ++= old
      ast
    }
    def representationConstructor(syntax: Value): Ast = {
      val id         = string(syntax, "constructor")
      val target     = sym(id)
      val previous   = declarations.toMap
      val parameters = (Seq("this" -> currentType) ++ strings(target, "parameters").map(id =>
        string(sym(id), "name") -> tpe(sym(id))
      )).zipWithIndex.map { case ((name, typ), index) =>
        val parameter = located(
          NewMethodParameterIn()
            .name(name)
            .code(name)
            .index(index)
            .order(index)
            .typeFullName(typ)
            .evaluationStrategy(if (index == 0) EvaluationStrategies.BY_REFERENCE else EvaluationStrategies.BY_VALUE)
            .isVariadic(false),
          syntax
        )
        declarations(if (index == 0) "this" else strings(target, "parameters")(index - 1)) = parameter
        Ast(parameter)
      }
      val valueId = strings(target, "parameters").head
      val assign  = operator(
        syntax,
        Operators.assignment,
        Seq(
          field(syntax, thisAst(syntax), string(syntax, "name")),
          identifier(syntax, string(sym(valueId), "name"), valueId, tpe(sym(valueId)))
        )
      )
      val out = located(
        NewMethod()
          .name(if (string(target, "name") == "new") "<init>" else string(target, "name"))
          .fullName(id)
          .code(code(syntax))
          .filename(filename)
          .isExternal(false)
          .signature(s"$currentType(1)")
          .astParentType(ownerType)
          .astParentFullName(owner),
        syntax
      )
      val ast = Ast(out)
        .withChildren(parameters)
        .withChild(block(syntax, Seq(assign, args(NewReturn().code("return this"), Seq(thisAst(syntax))))))
        .withChild(
          Ast(NewMethodReturn().typeFullName(currentType).code("RET").evaluationStrategy(EvaluationStrategies.BY_VALUE))
        )
        .withChild(Ast(NewModifier().modifierType(ModifierTypes.CONSTRUCTOR)))
      declarations.clear(); declarations ++= previous
      ast
    }
    def typeParameters(syntax: Value, parentType: String, parent: String): Seq[Ast] =
      children(syntax, "typeParameters").flatMap(list => children(list, "parameter")).map { parameter =>
        val target = symbol(parameter)
        val out    = located(
          NewTypeDecl()
            .name(string(parameter, "name"))
            .fullName(
              string(
                parameter,
                "declaration",
                s"$filename#${parameter("offset").num.toInt}:TYPE_PARAMETER:${string(parameter, "name")}"
              )
            )
            .code(code(parameter))
            .filename(filename)
            .isExternal(false)
            .inheritsFromTypeFullName(Seq(string(target, "boundTypeId")).filter(_.nonEmpty))
            .genericSignature(s"${string(parameter, "name")} extends ${string(target, "boundType", "Object?")}")
            .astParentType(parentType)
            .astParentFullName(parent),
          parameter
        )
        Ast(out)
          .withChild(Ast(NewAnnotation().name("typeParameter").fullName("dart.typeParameter").code("typeParameter")))
          .withChildren(signatureDeclarations(string(parameter, "declaration"), "TYPE_DECL", out.fullName))
      }
    def signatureDeclarations(scope: String, parentType: String, parent: String): Seq[Ast] =
      signatureTypes.remove(scope).toSeq.flatten.map { syntax =>
        val id  = functionTypeId(syntax)
        val out = located(
          NewTypeDecl()
            .name("<functionType>")
            .fullName(id)
            .code(code(syntax))
            .filename(filename)
            .isExternal(false)
            .aliasTypeFullName(tpe(syntax))
            .genericSignature(tpe(syntax))
            .astParentType(parentType)
            .astParentFullName(parent),
          syntax
        )
        Ast(out)
          .withChildren(typeParameters(syntax, "TYPE_DECL", id))
          .withChildren(signatureDeclarations(id, "TYPE_DECL", id))
          .withChild(Ast(NewAnnotation().name("functionType").fullName("dart.functionType").code("functionType")))
      }
    def declaration(syntax: Value): Seq[Ast] = string(syntax, "kind") match {
      case "GenericTypeAlias" | "FunctionTypeAlias" =>
        val out = located(
          NewTypeDecl()
            .name(string(syntax, "name"))
            .fullName(
              string(
                syntax,
                "declaration",
                s"$filename#${syntax("offset").num.toInt}:TYPE_ALIAS:${string(syntax, "name")}"
              )
            )
            .aliasTypeFullName(string(syntax, "aliasedType", "ANY"))
            .genericSignature(code(syntax))
            .code(code(syntax))
            .filename(filename)
            .isExternal(false)
            .astParentType(ownerType)
            .astParentFullName(owner),
          syntax
        )
        Seq(
          Ast(out)
            .withChildren(typeParameters(syntax, "TYPE_DECL", out.fullName))
            .withChildren(signatureDeclarations(string(syntax, "declaration"), "TYPE_DECL", out.fullName))
        )
      case "FunctionDeclaration"                              => Seq(method(syntax, child(syntax, "function")))
      case "MethodDeclaration" | "ConstructorDeclaration"     => Seq(method(syntax, syntax))
      case "TopLevelVariableDeclaration" | "FieldDeclaration" =>
        children(child(syntax, "variables"), "variable").flatMap { variable =>
          Seq(member(variable)) ++ (if (lazyMembers.contains(string(variable, "declaration"))) lazyAccessors(variable)
                                    else Nil)
        }
      case "ClassDeclaration" | "ClassTypeAlias" | "EnumDeclaration" | "MixinDeclaration" | "ExtensionDeclaration" |
          "ExtensionTypeDeclaration" =>
        val previousOwner       = owner
        val previousType        = ownerType
        val previousCurrentType = currentType
        val previousFields      = instanceFields
        owner = string(syntax, "declaration", s"$filename#${syntax("offset").num.toInt}:TYPE:${string(syntax, "name")}")
        ownerType = "TYPE_DECL"
        currentType = owner
        val members        = children(syntax, "member")
        val representation = children(syntax, "representation")
        val constants      = children(syntax, "constant")
        val generatedEnums = if (string(syntax, "kind") == "EnumDeclaration") enumMembers(syntax) else Nil
        val variables      = members
          .filter(memberSyntax => string(memberSyntax, "kind") == "FieldDeclaration")
          .flatMap(memberSyntax => children(child(memberSyntax, "variables"), "variable"))
        instanceFields = variables.filterNot(fieldSyntax => bool(symbol(fieldSyntax), "static"))
        val fields =
          members.filter(memberSyntax => string(memberSyntax, "kind") == "FieldDeclaration").flatMap(declaration)
        val methods =
          members.filterNot(memberSyntax => string(memberSyntax, "kind") == "FieldDeclaration").flatMap(declaration)
        val staticFields = constants ++ variables.filter(fieldSyntax =>
          bool(symbol(fieldSyntax), "static") && children(fieldSyntax, "initializer").nonEmpty
        )
        val initializers =
          (if (staticFields.nonEmpty || generatedEnums.nonEmpty)
             Seq(initializerMethod(syntax, staticFields, s"$owner:<clinit>", "<clinit>", false))
           else Nil) ++
            (if (string(syntax, "implicitConstructor").nonEmpty)
               Seq(initializerMethod(syntax, instanceFields, string(syntax, "implicitConstructor"), "<init>", true))
             else Nil)
        val forwardingConstructors = strings(syntax, "constructors").map { id =>
          val name = string(sym(id), "name")
          initializerMethod(syntax, Nil, id, if (name.isEmpty || name == "new") "<init>" else name, true, true)
        }
        val representationAsts = representation.map(member) ++ representation
          .filter(representationSyntax =>
            strings(sym(string(representationSyntax, "constructor")), "parameters").nonEmpty
          )
          .map(representationConstructor)
        val out = located(
          NewTypeDecl()
            .name(string(syntax, "name"))
            .fullName(currentType)
            .code(code(syntax))
            .filename(filename)
            .isExternal(false)
            .inheritsFromTypeFullName(strings(symbol(syntax), "superDeclarations"))
            .genericSignature(
              s"${string(syntax, "name")}${children(syntax, "typeParameters").headOption.map(code).getOrElse("")}"
            )
            .astParentType(previousType)
            .astParentFullName(previousOwner),
          syntax
        )
        owner = previousOwner; ownerType = previousType; currentType = previousCurrentType;
        instanceFields = previousFields
        val modifiers = strings(syntax, "modifiers")
        val standard  = modifiers
          .collect {
            case "abstract" | "sealed" => ModifierTypes.ABSTRACT
            case "final"               => ModifierTypes.FINAL
          }
          .distinct
          .map(modifier => Ast(NewModifier().modifierType(modifier)))
        val annotations =
          modifiers.map(modifier => Ast(NewAnnotation().name(modifier).fullName(s"dart.$modifier").code(modifier)))
        Seq(
          Ast(out).withChildren(
            typeParameters(syntax, "TYPE_DECL", out.fullName) ++
              signatureDeclarations(string(syntax, "declaration"), "TYPE_DECL", out.fullName) ++ fields ++ constants.map(
                member
              ) ++ generatedEnums ++ representationAsts ++ methods ++ initializers ++ forwardingConstructors ++ standard ++ annotations
          )
        )
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
            .importedEntity(string(syntax, "selectedUri", uri))
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
      if (globalFields.nonEmpty)
        Seq(initializerMethod(nodes.head, globalFields, s"$filename:<global>:<clinit>", "<clinit>", false))
      else Nil
    val file = NewFile().name(filename)
    if (!config.disableFileContent) file.content(source)
    val globalSignatures = signatureTypes.keys.toSeq.sorted.flatMap(scope =>
      signatureDeclarations(scope, "NAMESPACE_BLOCK", namespace.fullName)
    )
    Ast.storeInDiffGraph(
      Ast(file).withChild(
        Ast(namespace).withChildren(imports ++ fields ++ asts ++ initializers ++ extraMethods ++ globalSignatures)
      ),
      diffGraph
    )
  }
}
