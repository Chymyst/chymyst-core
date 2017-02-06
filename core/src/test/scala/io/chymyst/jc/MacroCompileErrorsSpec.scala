package io.chymyst.jc

import io.chymyst.jc.Core.ReactionBody
import utest._
import utest.framework.{Test, Tree}

import scala.concurrent.duration._

object MacroCompileErrorsSpec extends TestSuite {
  val tests: Tree[Test] = this {
    val a = m[Int]
    val c = m[Unit]
    val f = b[Int, Int]
    val x = 2

    assert(a.name == "a")
    assert(c.name == "c")
    assert(f.name == "f")
    assert(x == 2)

    "fail to compile molecules with non-unit types emitted as a()" - {

      * - {
        compileError(
          "val x = a()"
        ).check(
          """
            |          "val x = a()"
            |                    ^
            |""".stripMargin, "could not find implicit value for parameter arg: io.chymyst.jc.TypeMustBeUnit[Int]")
      }
      * - {
        compileError(
          "val x = f()"
        ).check(
          """
            |          "val x = f()"
            |                    ^
            |""".stripMargin, "could not find implicit value for parameter arg: io.chymyst.jc.TypeMustBeUnit[Int]")
      }
      * - {
        compileError(
          "val x = f.timeout()(1 second)"
        ).check(
          """
            |          "val x = f.timeout()(1 second)"
            |                              ^
            |""".stripMargin, "could not find implicit value for parameter arg: io.chymyst.jc.TypeMustBeUnit[Int]")
      }
      * - {
        compileError(
          "val r = go { case f(_, r) => r() } "
        ).check(
          """
            |          "val r = go { case f(_, r) => r() } "
            |                                         ^
            |""".stripMargin, "could not find implicit value for parameter arg: io.chymyst.jc.TypeMustBeUnit[Int]")
      }
      * - {
        compileError(
          "val r = go { case f(_, r) => r.checkTimeout() } "
        ).check(
          """
            |          "val r = go { case f(_, r) => r.checkTimeout() } "
            |                                                      ^
            |""".stripMargin, "could not find implicit value for parameter arg: io.chymyst.jc.TypeMustBeUnit[Int]")
      }
    }

    "fail to compile a reaction with empty static clause" - {
      compileError(
        "val r = go { case _ => }"
      ).check(
        """
          |        "val r = go { case _ => }"
          |                    ^
          |""".stripMargin, "Static reaction must emit some output molecules")
    }

    "fail to compile a guard that replies to molecules" - {
      * - {
        compileError(
          "val r = go { case f(_, r) if { r(1); x > 0 } => }"
        ).check(
          """
            |          "val r = go { case f(_, r) if { r(1); x > 0 } => }"
            |                      ^
            |""".stripMargin, "Input guard must not perform any reply actions (r)")
      }
      * - {
        compileError(
          "val r = go { case f(_, r) if r.checkTimeout(1) && x > 0 => }"
        ).check(
          """
            |          "val r = go { case f(_, r) if r.checkTimeout(1) && x > 0 => }"
            |                      ^
            |""".stripMargin, "Input guard must not perform any reply actions (r)")

      }
    }

    "fail to compile a guard that emits molecules" - {
      * - {
        compileError(
          "val r = go { case f(_, r) if f(1) > 0 => r(1) }"
        ).check(
          """
            |          "val r = go { case f(_, r) if f(1) > 0 => r(1) }"
            |                      ^
            |""".stripMargin, "Input guard must not emit any output molecules (f)")
      }

      * - {
        compileError(
          "val r = go { case f(_, r) if f.timeout(1)(1.second).nonEmpty => r(1) }"
        ).check(
          """
            |          "val r = go { case f(_, r) if f.timeout(1)(1.second).nonEmpty => r(1) }"
            |                      ^
            |""".stripMargin, "Input guard must not emit any output molecules (f)")
      }
    }

    "fail to compile a reaction with two case clauses" - {
      compileError(
        "val r = go { case a(x) =>; case c(_) => }"
      ).check(
        """
          |        "val r = go { case a(x) =>; case c(_) => }"
          |                    ^
          |""".stripMargin, "Reactions must contain only one `case` clause")
    }

    "fail to compile a reaction that is not defined inline" - {
      val body: ReactionBody = {
        case _ => c()
      }
      assert(body.isInstanceOf[ReactionBody])
      compileError(
        "val r = go(body)"
      ).check(
        """
          |        "val r = go(body)"
          |                   ^
          |""".stripMargin, "No `case` clauses found: Reactions must be defined inline with the `go { case ... => ... }` syntax")
    }


  }
}