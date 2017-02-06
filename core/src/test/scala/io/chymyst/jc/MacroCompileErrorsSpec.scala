package io.chymyst.jc

import utest._
import utest.framework.{Test, Tree}

import scala.concurrent.duration._

object MacroCompileErrorsSpec extends TestSuite {
  val tests: Tree[Test] = this {
    "fail to compile molecules with non-unit types emitted as a()" - {
      val a = m[Int]
      val c = m[Unit]
      val f = b[Int, Int]

      assert(a.name == "a")
      assert(c.name == "c")
      assert(f.name == "f")

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

  }
}