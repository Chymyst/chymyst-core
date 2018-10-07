package io.chymyst.jc

import io.chymyst.test.LogSpec
import org.scalatest.Matchers

// Note: Compilation of this test suite will generate warnings such as "crushing into 2-tuple". This is expected and cannot be avoided.

class MacroErrorSpec extends LogSpec with Matchers {

  it should "support concise syntax for Unit-typed molecules" in {
    val a = new M[Unit]("a")
    val f = new B[Unit, Unit]("f")
    val h = b[Unit, Boolean]
    val g = b[Unit, Unit]
    // This should compile without any argument adaptation warnings:
    go { case a(_) + f(_, r) + g(_, s) + h(_, q) => a() + f(); s(); val status = r(); q(status) }
  }

  it should "compile a reaction within scalatest scope" in {
    val x = m[Int]
    site(go { case x(_) => }) shouldEqual WarningsAndErrors(List(), List(), "Site{x → ...}")
  }

  it should "compile a reaction with scoped pattern variables" in {
    val a = m[(Int, Int)]
    site(go { case a(y@(_, q@_)) => }) shouldEqual WarningsAndErrors(List(), List(), "Site{a → ...}")

    val c = m[(Int, (Int, Int))]
    c.name shouldEqual "c"
    // TODO: make this compile in actual code, not just inside scalatest macro. This is github issue #109
    "val r1 = go { case c(y@(_, z@(_, _))) => }" should compile // But this actually fails to compile when used in the code!

    val d = m[(Int, Option[Int])]
    "val r2 = go { case d((x, z@Some(_))) => }" should compile // But this actually fails to compile when used in the code!

    site(
      go { case d((x, z)) if z.nonEmpty => } // ignore warning about "non-variable type argument Int"
    ) shouldEqual WarningsAndErrors(List(), List(), "Site{d → ...}")
  }

  behavior of "output environments"

  it should "refuse emitting blocking molecules" in {
    val c = m[Unit]
    val f = b[Unit, Unit]
    val g: Any => Any = x => x

    assert(c.name == "c")
    assert(f.name == "f")
    assert(g(()) == (()))

    // For some reason, utest cannot get the compiler error message for this case.
    // So we have to move this test back to scalatest, even though here we can't check the error message.
    "val r = go { case c(_) => (0 until 10).foreach{_ => g(f); () } }" shouldNot compile
  }

  it should "recognize emitter under finally{}" in {
    val a = m[Unit]
    val c = m[Unit]
    val reaction = go { case a(_) ⇒
      try
        throw new Exception("")
      catch {
        case _: Exception ⇒
      } finally c()
    }
    reaction.info.outputs.length shouldEqual 1
    reaction.info.outputs(0).environments.toList should matchPattern { case List() ⇒ }
  }

  behavior of "compile-time errors due to chemistry"

  it should "inspect a pattern with a compound constant" in {
    val a = m[(Int, Int)]
    val c = m[Unit]
    val reaction = go { case a((1, _)) + c(_) => a((1, 1)) }
    reaction.info.inputs.head.flag should matchPattern { case OtherInputPattern(_, List(), false) => }
    (reaction.info.inputs.head.flag match {
      case OtherInputPattern(matcher, List(), false) =>
        matcher.isDefinedAt((1, 1)) shouldEqual true
        matcher.isDefinedAt((1, 2)) shouldEqual true
        matcher.isDefinedAt((0, 1)) shouldEqual false
        true
      case _ => false
    }) shouldEqual true
  }

  it should "inspect a pattern with a crushed tuple" in {

    val bb = m[(Int, Option[Int])]

    val result = go { // ignore warning about "non-variable type argument Int"

      // This generates a compiler warning "class M expects 2 patterns to hold (Int, Option[Int]) but crushing into 2-tuple to fit single pattern (SI-6675)".
      // However, this "crushing" is precisely what this test focuses on, and we cannot tell scalac to ignore this warning.
      case bb(_) + bb(z) if (z match // ignore warning about "class M expects 2 patterns to hold"
      {
        case (1, Some(x)) if x > 0 => true;
        case _ => false
      }) =>
    }

    (result.info.inputs.toList match {
      case List(
      InputMoleculeInfo(`bb`, 'bb, 0, WildcardInput, _, Symbol("(Int, Option[Int])")),
      InputMoleculeInfo(`bb`, 'bb, 1, SimpleVarInput('z, Some(cond)), _, _)
      ) =>
        cond.isDefinedAt((1, Some(2))) shouldEqual true
        cond.isDefinedAt((1, None)) shouldEqual false
        cond.isDefinedAt((1, Some(0))) shouldEqual false
        cond.isDefinedAt((0, Some(2))) shouldEqual false
        true
      case _ => false
    }) shouldEqual true

  }

}
