package io.joern.c2cpg.dataflow

import io.joern.c2cpg.testfixtures.DataFlowCodeToCpgSuite
import io.joern.dataflowengineoss.language.*
import io.shiftleft.semanticcpg.language.*

class ComparisonFlowTests extends DataFlowCodeToCpgSuite {
  "Primitive comparisons" should {
    for (operator <- Seq("==", "!=", "<", ">", "<=", ">=")) {
      s"preserve the result dependency without modifying operands for $operator" in {
        val cpg = code(s"""
          |void sink(int value) {}
          |int compare(int input, int independent) {
          |  int result = input $operator independent;
          |  sink(independent);
          |  return result;
          |}
          |""".stripMargin)
        cpg.call
          .nameExact("sink")
          .argument(1)
          .reachableByFlows(cpg.method.nameExact("compare").parameter.nameExact("input"))
          .isEmpty shouldBe true
        cpg.method
          .nameExact("compare")
          .methodReturn
          .reachableByFlows(cpg.method.nameExact("compare").parameter.nameExact("input"))
          .nonEmpty shouldBe true
        cpg.call
          .nameExact("sink")
          .argument(1)
          .reachableByFlows(cpg.method.nameExact("compare").parameter.nameExact("independent"))
          .nonEmpty shouldBe true
      }
    }
  }
}
