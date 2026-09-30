package io.joern.c2cpg.dataflow

import io.joern.c2cpg.testfixtures.DataFlowCodeToCpgSuite
import io.joern.dataflowengineoss.language.*
import io.shiftleft.semanticcpg.language.*

class PrimitiveOperatorFlowTests extends DataFlowCodeToCpgSuite {
  "Primitive arithmetic and logical operators" should {
    for (operator <- Seq("+", "-", "*", "/", "%", "&", "|", "^", "<<", ">>", "&&", "||")) {
      s"preserve result dependencies without writing operands for $operator" in {
        val cpg = code(s"""
          |void sink(int value) {}
          |int calculate(int input, int independent) {
          |  int result = input $operator independent;
          |  sink(independent);
          |  return result;
          |}
          |""".stripMargin)
        cpg.call
          .nameExact("sink")
          .argument(1)
          .reachableByFlows(cpg.method.nameExact("calculate").parameter.nameExact("input"))
          .isEmpty shouldBe true
        cpg.method
          .nameExact("calculate")
          .methodReturn
          .reachableByFlows(cpg.method.nameExact("calculate").parameter.nameExact("input"))
          .nonEmpty shouldBe true
        cpg.call
          .nameExact("sink")
          .argument(1)
          .reachableByFlows(cpg.method.nameExact("calculate").parameter.nameExact("independent"))
          .nonEmpty shouldBe true
      }
    }
  }
}
