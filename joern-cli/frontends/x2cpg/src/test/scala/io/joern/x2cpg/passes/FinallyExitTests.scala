package io.joern.x2cpg.passes

import flatgraph.misc.TestUtils.*
import io.joern.x2cpg.passes.controlflow.CfgCreationPass
import io.shiftleft.codepropertygraph.generated.{ControlStructureTypes, Cpg, EdgeTypes}
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.language.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class FinallyExitTests extends AnyWordSpec with Matchers {
  "Finally control flow" should {
    "consume protected throws at an explicitly unconditional catch without swallowing handler failures" in {
      for (unconditional <- Seq(false, true); invocation <- Seq(false, true)) {
        val cpg = Cpg.empty
        try {
          val graph     = cpg.graph
          val method    = graph.addNode(NewMethod().name("handled").fullName("handled"))
          val exit      = graph.addNode(NewMethodReturn().order(2))
          val body      = graph.addNode(NewBlock().order(1))
          val outer     = graph.addNode(NewControlStructure().controlStructureType(ControlStructureTypes.TRY).order(1))
          val outerBody = graph.addNode(NewBlock().order(1))
          val outerHandler = graph.addNode(NewBlock().order(2))
          val outerRead    = graph.addNode(NewLiteral().code("outer handler").order(1))
          val inner     = graph.addNode(NewControlStructure().controlStructureType(ControlStructureTypes.TRY).order(1))
          val innerBody = graph.addNode(NewBlock().order(1))
          val handler = graph.addNode(NewControlStructure().controlStructureType(ControlStructureTypes.CATCH).order(2))
          val condition         = graph.addNode(NewLiteral().code("true").typeFullName("boolean").order(1))
          val handlerBody       = graph.addNode(NewBlock().order(2))
          val original: CfgNode =
            if (invocation) graph.addNode(NewCall().name("original").code("original").order(1))
            else
              graph.addNode(
                NewControlStructure().controlStructureType(ControlStructureTypes.THROW).code("original").order(1)
              )
          val replacement = graph.addNode(
            NewControlStructure().controlStructureType(ControlStructureTypes.THROW).code("replacement").order(1)
          )
          graph.applyDiff { diff =>
            for (
              (parent, child) <- Seq(
                method       -> body,
                method       -> exit,
                body         -> outer,
                outer        -> outerBody,
                outer        -> outerHandler,
                outerHandler -> outerRead,
                outerBody    -> inner,
                inner        -> innerBody,
                inner        -> handler,
                innerBody    -> original,
                handler      -> condition,
                handler      -> handlerBody,
                handlerBody  -> replacement
              )
            ) diff.addEdge(parent, child, EdgeTypes.AST)
            diff.addEdge(outer, outerBody, EdgeTypes.TRY_BODY)
            diff.addEdge(outer, outerHandler, EdgeTypes.CATCH_BODY)
            diff.addEdge(inner, innerBody, EdgeTypes.TRY_BODY)
            diff.addEdge(inner, handler, EdgeTypes.CATCH_BODY)
            if (unconditional) diff.addEdge(handler, condition, EdgeTypes.CONDITION)
          }
          new CfgCreationPass(cpg).createAndApply()
          val targets = original.out(EdgeTypes.CFG).cast[CfgNode].toSet
          targets should contain(condition)
          targets.contains(outerRead) shouldBe !unconditional
          replacement.out(EdgeTypes.CFG).cast[CfgNode].toSet should contain(outerRead)
        } finally cpg.close()
      }
    }
    "never reinterpret an explicitly linked second catch as a finally body" in {
      val cpg = Cpg.empty
      try {
        val graph     = cpg.graph
        val method    = graph.addNode(NewMethod().name("catches").fullName("catches"))
        val exit      = graph.addNode(NewMethodReturn().order(2))
        val body      = graph.addNode(NewBlock().order(1))
        val statement = graph.addNode(NewControlStructure().controlStructureType(ControlStructureTypes.TRY).order(1))
        val blocks    = (1 to 3).map(order => graph.addNode(NewBlock().order(order)))
        val returns   = (1 to 3).map(index => graph.addNode(NewReturn().code(s"return $index").order(1)))
        graph.applyDiff { diff =>
          diff.addEdge(method, body, EdgeTypes.AST)
          diff.addEdge(method, exit, EdgeTypes.AST)
          diff.addEdge(body, statement, EdgeTypes.AST)
          for ((block, ret) <- blocks.zip(returns)) {
            diff.addEdge(statement, block, EdgeTypes.AST)
            diff.addEdge(block, ret, EdgeTypes.AST)
          }
          diff.addEdge(statement, blocks.head, EdgeTypes.TRY_BODY)
          blocks.tail.foreach(block => diff.addEdge(statement, block, EdgeTypes.CATCH_BODY))
        }
        new CfgCreationPass(cpg).createAndApply()
        returns.foreach(_.out(EdgeTypes.CFG).cast[CfgNode].toSet shouldBe Set(exit))
      } finally cpg.close()
    }
    "run cleanup before explicit returns, throws and loop exits" in {
      for (kind <- Seq("return", "throw", "break", "continue"); nested <- Seq(false, true)) {
        val cpg = Cpg.empty
        try {
          val graph  = cpg.graph
          val method = graph.addNode(NewMethod().name("test").fullName("test"))
          val exit   = graph.addNode(NewMethodReturn().order(2))
          val body   = graph.addNode(NewBlock().order(1))
          val loop   = graph.addNode(NewControlStructure().controlStructureType(ControlStructureTypes.WHILE).order(1))
          val condition = graph.addNode(NewLiteral().code("condition").order(1))
          val loopBody  = graph.addNode(NewBlock().order(2))
          val statement = graph.addNode(NewControlStructure().controlStructureType(ControlStructureTypes.TRY).order(1))
          val protectedBody   = graph.addNode(NewBlock().order(1))
          val cleanupBody     = graph.addNode(NewBlock().order(2))
          val abrupt: CfgNode =
            if (kind == "return") graph.addNode(NewReturn().code("return").order(1))
            else graph.addNode(NewControlStructure().controlStructureType(kind.toUpperCase).code(kind).order(1))
          val cleanup   = graph.addNode(NewCall().name("cleanup").code("cleanup").order(1))
          val after     = graph.addNode(NewCall().name("after").code("after").order(2))
          val inner     = graph.addNode(NewControlStructure().controlStructureType(ControlStructureTypes.TRY).order(1))
          val innerBody = graph.addNode(NewBlock().order(1))
          val innerFinally = graph.addNode(NewBlock().order(2))
          val innerCleanup = graph.addNode(NewCall().name("innerCleanup").code("innerCleanup").order(1))
          graph.applyDiff { diff =>
            for (
              (parent, child) <- Seq(
                method        -> body,
                method        -> exit,
                body          -> loop,
                body          -> after,
                loop          -> condition,
                loop          -> loopBody,
                loopBody      -> statement,
                statement     -> protectedBody,
                statement     -> cleanupBody,
                protectedBody -> (if (nested) inner else abrupt),
                cleanupBody   -> cleanup
              )
            )
              diff.addEdge(parent, child, EdgeTypes.AST)
            if (nested) {
              for (
                (parent, child) <- Seq(
                  inner        -> innerBody,
                  inner        -> innerFinally,
                  innerBody    -> abrupt,
                  innerFinally -> innerCleanup
                )
              )
                diff.addEdge(parent, child, EdgeTypes.AST)
              diff.addEdge(inner, innerBody, EdgeTypes.TRY_BODY)
              diff.addEdge(inner, innerFinally, EdgeTypes.FINALLY_BODY)
            }
            diff.addEdge(loop, condition, EdgeTypes.CONDITION)
            diff.addEdge(statement, protectedBody, EdgeTypes.TRY_BODY)
            diff.addEdge(statement, cleanupBody, EdgeTypes.FINALLY_BODY)
          }
          new CfgCreationPass(cpg).createAndApply()
          withClue(kind) {
            abrupt.out(EdgeTypes.CFG).cast[CfgNode].toList shouldBe List(if (nested) innerCleanup else cleanup)
            if (nested) innerCleanup.out(EdgeTypes.CFG).cast[CfgNode].toList shouldBe List(cleanup)
            val expected = kind match {
              case "break"    => after
              case "continue" => condition
              case _          => exit
            }
            cleanup.out(EdgeTypes.CFG).cast[CfgNode].toSet shouldBe (Set(expected) ++ (if (nested) Set(exit)
                                                                                       else Set.empty))
          }
        } finally cpg.close()
      }
    }
    "let an abrupt finally replace a pending return or throw" in {
      for (thrown <- Seq(false, true)) {
        val cpg = Cpg.empty
        try {
          val graph     = cpg.graph
          val method    = graph.addNode(NewMethod().name("overrideExit").fullName("overrideExit"))
          val exit      = graph.addNode(NewMethodReturn().order(2))
          val body      = graph.addNode(NewBlock().order(1))
          val statement = graph.addNode(NewControlStructure().controlStructureType(ControlStructureTypes.TRY).order(1))
          val protectedBody    = graph.addNode(NewBlock().order(1))
          val cleanupBody      = graph.addNode(NewBlock().order(2))
          val pending: CfgNode =
            if (thrown) graph.addNode(NewControlStructure().controlStructureType(ControlStructureTypes.THROW).order(1))
            else graph.addNode(NewReturn().order(1))
          val replacement = graph.addNode(NewReturn().code("replacement").order(1))
          graph.applyDiff { diff =>
            for (
              (parent, child) <- Seq(
                method        -> body,
                method        -> exit,
                body          -> statement,
                statement     -> protectedBody,
                statement     -> cleanupBody,
                protectedBody -> pending,
                cleanupBody   -> replacement
              )
            )
              diff.addEdge(parent, child, EdgeTypes.AST)
            diff.addEdge(statement, protectedBody, EdgeTypes.TRY_BODY)
            diff.addEdge(statement, cleanupBody, EdgeTypes.FINALLY_BODY)
          }
          new CfgCreationPass(cpg).createAndApply()
          pending.out(EdgeTypes.CFG).cast[CfgNode].toList shouldBe List(replacement)
          replacement.out(EdgeTypes.CFG).cast[CfgNode].toList shouldBe List(exit)
        } finally cpg.close()
      }
    }
    "offer a catch path from an early call rather than only the try fringe" in {
      val cpg = Cpg.empty
      try {
        val graph     = cpg.graph
        val method    = graph.addNode(NewMethod().name("catchEarly").fullName("catchEarly"))
        val exit      = graph.addNode(NewMethodReturn().order(2))
        val body      = graph.addNode(NewBlock().order(1))
        val statement = graph.addNode(NewControlStructure().controlStructureType(ControlStructureTypes.TRY).order(1))
        val protectedBody = graph.addNode(NewBlock().order(1))
        val catcher = graph.addNode(NewControlStructure().controlStructureType(ControlStructureTypes.CATCH).order(2))
        val early   = graph.addNode(NewCall().name("early").code("early").order(1))
        val later   = graph.addNode(NewCall().name("later").code("later").order(2))
        val handler = graph.addNode(NewCall().name("handler").code("handler").order(1))
        graph.applyDiff { diff =>
          for (
            (parent, child) <- Seq(
              method        -> body,
              method        -> exit,
              body          -> statement,
              statement     -> protectedBody,
              statement     -> catcher,
              protectedBody -> early,
              protectedBody -> later,
              catcher       -> handler
            )
          ) diff.addEdge(parent, child, EdgeTypes.AST)
          diff.addEdge(statement, protectedBody, EdgeTypes.TRY_BODY)
          diff.addEdge(statement, catcher, EdgeTypes.CATCH_BODY)
        }
        new CfgCreationPass(cpg).createAndApply()
        early.out(EdgeTypes.CFG).cast[CfgNode].toSet should contain allOf (later, handler)
      } finally cpg.close()
    }
    "run finally for outward labeled jumps while retaining jumps within the protected body" in {
      for (label <- Seq("inside", "outside")) {
        val cpg = Cpg.empty
        try {
          val graph     = cpg.graph
          val method    = graph.addNode(NewMethod().name("jump").fullName("jump"))
          val exit      = graph.addNode(NewMethodReturn().order(2))
          val body      = graph.addNode(NewBlock().order(1))
          val statement = graph.addNode(NewControlStructure().controlStructureType(ControlStructureTypes.TRY).order(1))
          val protectedBody = graph.addNode(NewBlock().order(1))
          val finalBody     = graph.addNode(NewBlock().order(2))
          val jump          =
            graph.addNode(NewControlStructure().controlStructureType(ControlStructureTypes.GOTO).code("jump").order(1))
          val target  = graph.addNode(NewJumpLabel().name(label).order(1))
          val inside  = graph.addNode(NewJumpTarget().name("inside").code("inside").order(2))
          val outside = graph.addNode(NewJumpTarget().name("outside").code("outside").order(2))
          val cleanup = graph.addNode(NewCall().name("cleanup").code("cleanup").order(1))
          graph.applyDiff { diff =>
            for (
              (parent, child) <- Seq(
                method        -> body,
                method        -> exit,
                body          -> statement,
                body          -> outside,
                statement     -> protectedBody,
                statement     -> finalBody,
                protectedBody -> jump,
                protectedBody -> inside,
                jump          -> target,
                finalBody     -> cleanup
              )
            ) diff.addEdge(parent, child, EdgeTypes.AST)
            diff.addEdge(jump, target, EdgeTypes.JUMP_ARGUMENT)
            diff.addEdge(statement, protectedBody, EdgeTypes.TRY_BODY)
            diff.addEdge(statement, finalBody, EdgeTypes.FINALLY_BODY)
          }
          new CfgCreationPass(cpg).createAndApply()
          jump.out(EdgeTypes.CFG).cast[CfgNode].toList shouldBe List(if (label == "inside") inside else cleanup)
          cleanup.out(EdgeTypes.CFG).cast[CfgNode].toList shouldBe List(outside)
        } finally cpg.close()
      }
    }
  }
}
