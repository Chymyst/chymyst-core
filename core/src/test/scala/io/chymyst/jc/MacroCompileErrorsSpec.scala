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
      * - {
        compileError(
          "val r = go { case a(x) =>; case c(_) => }"
        ).check(
          """
            |          "val r = go { case a(x) =>; case c(_) => }"
            |                      ^
            |""".stripMargin, "Reactions must contain only one `case` clause")
      }
      * - {
        compileError(
          """val result =
go {
case a(x) => c()
case c(_) + a(y) => c()
}""").check(
          """
            |go {
            |   ^
            |""".stripMargin, "Reactions must contain only one `case` clause")
      }
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

    "fail to compile reactions with unconditional livelock when all matchers are trivial" - {
      val a = m[(Int, Int)]
      val bb = m[Int]
      val bbb = m[Int]

      assert(a.isInstanceOf[M[(Int, Int)]])
      assert(bb.isInstanceOf[M[Int]])
      assert(bbb.isInstanceOf[M[Int]])

      * - {
        compileError(
          "val r = go { case a((x,y)) => a((1,1)) }"
        ).check(
          """
            |          "val r = go { case a((x,y)) => a((1,1)) }"
            |                      ^
          """.stripMargin, "Unconditional livelock: Input molecules must not be a subset of output molecules, with all trivial matchers for (a)")
      }
      * - {
        compileError(
          "val r = go { case a((_,x)) => a((x,x)) }"
        ).check(
          """
            |          "val r = go { case a((_,x)) => a((x,x)) }"
            |                      ^
            |""".stripMargin, "Unconditional livelock: Input molecules must not be a subset of output molecules, with all trivial matchers for (a)")
      }
      * - {
          val r = go { case a((1,_)) => a((1,1)) }
      } // cannot detect unconditional livelock here at compile time, since we can't evaluate the binder yet
      * - {
          val r = go { case bb(y) if y > 0 => bb(1) }
      } // no unconditional livelock due to guard
      * - {
          val r = go { case bb(y) =>  if (y > 0) bb(1) }
      } // no unconditional livelock due to `if` in reaction
      * - {
          val r = go { case bb(y) =>  if (y > 0) bbb(1) else bb(2) }
      } // no unconditional livelock due to `if` in reaction
      * - {
        compileError(
          "val r = go { case bb(x) =>  if (x > 0) bb(1) else bb(2) }"
        ).check(
          """
            |          "val r = go { case bb(x) =>  if (x > 0) bb(1) else bb(2) }"
            |                      ^
            |""".stripMargin, "Unconditional livelock: Input molecules must not be a subset of output molecules, with all trivial matchers for (bb)")
      } // unconditional livelock due to shrinkage of `if` in reaction
      * - {
          val r = go { case bbb(1) => bbb(2) }
      } // no unconditional livelock
      * - {
        compileError(
          "val r = go { case bb(x) => bb(1) }"
        ).check(
          """
            |          "val r = go { case bb(x) => bb(1) }"
            |                      ^
            |""".stripMargin, "Unconditional livelock: Input molecules must not be a subset of output molecules, with all trivial matchers for (bb)")
      } // unconditional livelock
      * - {
        compileError(
          "val r = go { case a(_) => a((1,1)) }"
        ).check(
          """
            |          "val r = go { case a(_) => a((1,1)) }"
            |                      ^
            |""".stripMargin, "Unconditional livelock: Input molecules must not be a subset of output molecules, with all trivial matchers for (a)")
      }
      // unconditional livelock
      * - {
        compileError(
          "val r = go { case bbb(_) => bbb(0) }"
        ).check(
          """
            |          "val r = go { case bbb(_) => bbb(0) }"
            |                      ^
            |""".stripMargin, "Unconditional livelock: Input molecules must not be a subset of output molecules, with all trivial matchers for (bbb)")
      }
      // unconditional livelock
      * - {
        compileError(
          "val r = go { case bbb(x) => bbb(x + 1) + bb(x) }"
        ).check(
          """
            |          "val r = go { case bbb(x) => bbb(x + 1) + bb(x) }"
            |                      ^
            |""".stripMargin, "Unconditional livelock: Input molecules must not be a subset of output molecules, with all trivial matchers for (bbb)")
      }
      * - {
        compileError(
          "val r = go { case bbb(x) + bb(y) => bbb(x + 1) + bb(x) + bb(y + 1) }"
        ).check(
          """
            |          "val r = go { case bbb(x) + bb(y) => bbb(x + 1) + bb(x) + bb(y + 1) }"
            |                      ^
            |""".stripMargin, "Unconditional livelock: Input molecules must not be a subset of output molecules, with all trivial matchers for (bbb, bb)")
      }
    }

  }
}