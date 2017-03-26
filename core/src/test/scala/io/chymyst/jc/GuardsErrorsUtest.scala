package io.chymyst.jc

import utest._

object GuardsErrorsUtest extends TestSuite {
  val tests = this {
    "recognize an identically false guard condition" - {
      val a = m[Int]
      val n = 10
      assert(a.isInstanceOf[M[Int]], n == 10)
      * - {
        compileError(
          "val result = go { case a(x) if false => }"
        ).check(
          """
            |          "val result = go { case a(x) if false => }"
            |                           ^
            |""".stripMargin, "Reaction must not have an identically false guard condition")
      }
      * - {
        compileError(
          "val result = go { case a(x) if false ^ false => }"
        ).check(
          """
            |          "val result = go { case a(x) if false ^ false => }"
            |                           ^
            |""".stripMargin, "Reaction must not have an identically false guard condition")
      }
      * - {
        compileError(
          "val result = go { case a(x) if !(false ^ !false) => }"
        ).check(
          """
            |          "val result = go { case a(x) if !(false ^ !false) => }"
            |                           ^
            |""".stripMargin, "Reaction must not have an identically false guard condition")
      }
      * - {
        compileError(
          "val result = go { case a(x) if false || (true && false) || !true && n > 0 => }"
        ).check(
          """
            |          "val result = go { case a(x) if false || (true && false) || !true && n > 0 => }"
            |                           ^
            |""".stripMargin, "Reaction must not have an identically false guard condition")
      }
      * - {
        compileError(
          "val result = go { case a(x) if false ^ (true && false) || !true && n > 0 => }"
        ).check(
          """
            |          "val result = go { case a(x) if false ^ (true && false) || !true && n > 0 => }"
            |                           ^
            |""".stripMargin, "Reaction must not have an identically false guard condition")
      }
    }
  }
}