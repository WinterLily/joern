package io.joern.x2cpg.frontendspecific

import io.shiftleft.codepropertygraph.generated.Operators

// The pinned CPG schema does not yet provide Languages.DART.
object DartLanguage {
  final val Name        = "DART"
  val readOnlyOperators = Set(
    Operators.subtraction,
    Operators.multiplication,
    Operators.division,
    Operators.and,
    Operators.or,
    Operators.xor,
    Operators.shiftLeft,
    Operators.arithmeticShiftRight,
    Operators.logicalShiftRight,
    Operators.logicalAnd,
    Operators.logicalOr,
    Operators.equals,
    Operators.notEquals,
    Operators.lessThan,
    Operators.lessEqualsThan,
    Operators.greaterThan,
    Operators.greaterEqualsThan,
    "<operator>.isInitialized",
    "<operator>.patternShape"
  )

  def operatorName(name: String): String =
    if (readOnlyOperators.contains(name)) name.replace("<operator>.", "<operator>.dart.") else name

}
