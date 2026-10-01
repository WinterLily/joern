package io.joern.c2cpg.dataflow

import io.joern.c2cpg.testfixtures.DataFlowCodeToCpgSuite
import io.joern.dataflowengineoss.language.*
import io.shiftleft.semanticcpg.language.*

class BlockExpressionTests extends DataFlowCodeToCpgSuite {
  "Nested statement expressions" should {
    for ((value, expected) <- Seq("input" -> true, "0" -> false)) {
      s"use the final $value value in returns and operator arguments" in {
        val cpg = code(s"""
          |int direct(int input) { return ({ input; ({ input; $value; }); }); }
          |int argument(int input) { return +({ input; ({ input; $value; }); }); }
          |""".stripMargin)
        for (name <- Seq("direct", "argument")) {
          val method = cpg.method.nameExact(name).head
          withClue(name) {
            method.ast.isReturn.reachableByFlows(method.parameter.nameExact("input")).nonEmpty shouldBe expected
          }
        }
      }
    }
  }
}
