package code.chymyst.jc

import code.chymyst.jc.Core.ReactionBody
import org.scalatest.{FlatSpec, Matchers}

// Note: Compilation of this test suite will generate warnings such as "crushing into 2-tuple". This is expected and cannot be avoided.

class MacroErrorSpec extends FlatSpec with Matchers {

  behavior of "miscellaneous compile-time errors"

  it should "fail to compile a reaction with empty singleton clause" in {
    "val r = go { case _ => }" shouldNot compile
  }

  it should "fail to compile a guard that replies" in {
    val f = b[Unit, Unit]
    val x = 2
    x shouldEqual 2
    f.isInstanceOf[B[Unit, Unit]] shouldEqual true

    "val r = go { case f(_, r) if r() && x == 2 => }" shouldNot compile
  }

  it should "fail to compile a reaction that is not defined inline" in {
    val a = m[Unit]
    val body: ReactionBody = {
      case _ => a()
    }
    body.isInstanceOf[ReactionBody] shouldEqual true

    "val r = go(body)" shouldNot compile
  }

  it should "fail to compile a reaction with two case clauses" in {
    val a = m[Unit]
    val b = m[Unit]

    a.isInstanceOf[M[Unit]] shouldEqual true
    b.isInstanceOf[M[Unit]] shouldEqual true

    "val r = go { case a(_) =>; case b(_) => }" shouldNot compile
  }

  it should "compile a reaction within scalatest scope" in {
    val x = m[Int]
    site(go { case x(_) => }) shouldEqual WarningsAndErrors(List(), List(), "Site{x => ...}")
  }

  it should "compile a reaction with scoped pattern variables" in {
    val a = m[(Int, Int)]
    site(go { case a(y@(_, q@_)) => }) shouldEqual WarningsAndErrors(List(), List(), "Site{a => ...}")

    val c = m[(Int, (Int, Int))]
    c.name shouldEqual "c"
    // TODO: make this compile in actual code, not just inside scalatest macro
    "val r1 = go { case c(y@(_, z@(_, _))) => }" should compile // But this actually fails to compile when used in the code!

    val d = m[(Int, Option[Int])]
    "val r2 = go { case d((x, z@Some(_))) => }" should compile // But this actually fails to compile when used in the code!

    site(go { case d((x, z)) if z.nonEmpty => }) shouldEqual WarningsAndErrors(List(), List(), "Site{d => ...}")
  }

  it should "inspect reaction body with two cases" in {
    val a = m[Int]
    val qq = m[Unit]

    a.isInstanceOf[M[Int]] shouldEqual true
    qq.isInstanceOf[M[Unit]] shouldEqual true

    """val result = go {
      case a(x) => qq()
      case qq(_) + a(y) => qq()
    }""" shouldNot compile
  }

  it should "fail to compile reactions with incorrect pattern matching" in {
    val a = b[Unit, Unit]
    val c = b[Unit, Unit]
    val e = m[Unit]

    a.isInstanceOf[B[Unit, Unit]] shouldEqual true
    c.isInstanceOf[B[Unit, Unit]] shouldEqual true
    e.isInstanceOf[M[Unit]] shouldEqual true

    // Note: these tests will produce several warnings "expects 2 patterns to hold but crushing into 2-tuple to fit single pattern".
    // However, it is precisely this crushing that we are testing here, that actually should not compile with our `go` macro.
    // So, these warnings cannot be removed here and should be ignored.
    "val r = go { case e() => }" shouldNot compile // no pattern variable in a non-blocking molecule "e"
    "val r = go { case e(_,_) => }" shouldNot compile // two pattern variables in a non-blocking molecule "e"
    "val r = go { case e(_,_,_) => }" shouldNot compile // two pattern variables in a non-blocking molecule "e"

    "val r = go { case a() => }" shouldNot compile // no pattern variable for reply in "a"
    "val r = go { case a(_) => }" shouldNot compile // no pattern variable for reply in "a"
    "val r = go { case a(_, _) => }" shouldNot compile // no pattern variable for reply in "a"
    "val r = go { case a(_, _, _) => }" shouldNot compile // no pattern variable for reply in "a"
    "val r = go { case a(_, r) => }" shouldNot compile // no reply is performed with r
    "val r = go { case a(_, r) + a(_) + c(_) => r()  }" shouldNot compile // invalid patterns for "a" and "c"
    "val r = go { case a(_, r) + a(_) + c(_) => r(); r() }" shouldNot compile // two replies are performed with r, and invalid patterns for "a" and "c"

    "val r = go { case e(_) if true => c() }" should compile // input guard does not emit molecules
    "val r = go { case e(_) if c() => }" shouldNot compile // input guard emits molecules
    "val r = go { case a(_,r) if r() => }" shouldNot compile // input guard performs reply actions

    "val r = go { case e(_) => { case e(_) => } }" shouldNot compile // reaction body matches on input molecules
  }

  it should "fail to compile reactions with no input molecules" in {
    val bb = m[Int]
    val bbb = m[Int]

    bb.isInstanceOf[M[Int]] shouldEqual true
    bbb.isInstanceOf[M[Int]] shouldEqual true

    "val r = go { case _ => bb(0) }" should compile // declaration of a singleton
    "val r = go { case x => bb(x.asInstanceOf[Int]) }" shouldNot compile // no input molecules
    "val r = go { case x => x }" shouldNot compile // no input molecules
  }

  behavior of "compile-time errors due to chemistry"

  it should "fail to compile reactions with unconditional livelock" in {
    val a = m[(Int, Int)]
    val bb = m[Int]
    val bbb = m[Int]

    a.isInstanceOf[M[(Int, Int)]] shouldEqual true
    bb.isInstanceOf[M[Int]] shouldEqual true
    bbb.isInstanceOf[M[Int]] shouldEqual true

    "val r = go { case a((x,y)) => a((1,1)) }" shouldNot compile
    "val r = go { case a((_,x)) => a((x,x)) }" shouldNot compile
    "val r = go { case a((1,_)) => a((1,1)) }" should compile // cannot detect unconditional livelock here at compile time, since we can't evaluate the binder yet
    "val r = go { case bb(x) if x > 0 => bb(1) }" should compile // no unconditional livelock due to guard
    "val r = go { case bbb(1) => bbb(2) }" should compile // no unconditional livelock

    "val r = go { case bb(x) => bb(1) }" shouldNot compile // unconditional livelock

    "val r = go { case a(_) => a((1,1)) }" shouldNot compile // unconditional livelock

    "val r = go { case bbb(_) => bbb(0) }" shouldNot compile // unconditional livelock
    "val r = go { case bbb(x) => bbb(x) + bb(x) }" shouldNot compile
    "val r = go { case bbb(x) + bb(y) => bbb(x) + bb(x) + bb(y) }" shouldNot compile
  }

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

    val result = go {
      // This generates a compiler warning "class M expects 2 patterns to hold (Int, Option[Int]) but crushing into 2-tuple to fit single pattern (SI-6675)".
      // However, this crushing is precisely what this test focuses on, and we cannot tell scalac to ignore this warning.
      case bb(_) + bb(z) if (z match {
        case (1, Some(x)) if x > 0 => true;
        case _ => false
      }) =>
    }

    (result.info.inputs match {
      case Array(
      InputMoleculeInfo(`bb`, 0, Wildcard, _),
      InputMoleculeInfo(`bb`, 1, SimpleVar('z, Some(cond)), _)
      ) =>
        cond.isDefinedAt((1, Some(2))) shouldEqual true
        cond.isDefinedAt((1, None)) shouldEqual false
        cond.isDefinedAt((1, Some(0))) shouldEqual false
        cond.isDefinedAt((0, Some(2))) shouldEqual false
        true
      case _ => false
    }) shouldEqual true

  }

  it should "refuse reactions that match on other molecules in molecule input values" in {
    val a = m[Any]
    val f = b[Any, Any]
    a.name shouldEqual "a"
    f.name shouldEqual "f"

    go { case a(1) => a(a(1)) } // OK

    "val r = go { case a(a(1)) => }" shouldNot compile
    "val r = go { case f(_, 123) => }" shouldNot compile
    "val r = go { case f(a(1), r) => r(1) }" shouldNot compile
    "val r = go { case f(f(1,s), r) => r(1) }" shouldNot compile
  }

}