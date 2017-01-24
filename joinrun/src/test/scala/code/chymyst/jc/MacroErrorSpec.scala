package code.chymyst.jc

import code.chymyst.jc.Core.ReactionBody
import org.scalatest.{FlatSpec, Matchers}
import scala.concurrent.duration._

// Note: Compilation of this test suite will generate warnings such as "crushing into 2-tuple". This is expected and cannot be avoided.

class MacroErrorSpec extends FlatSpec with Matchers {

  behavior of "miscellaneous compile-time errors"

  it should "fail to compile molecules with non-unit types emitted as a()" in {
    val a = m[Int]
    val c = m[Unit]
    val f = b[Int, Int]
    a.name shouldEqual "a"
    c.name shouldEqual "c"
    f.name shouldEqual "f"

    1.second shouldEqual 1000.millis

    "val x = c(())" should compile
    "val x = c()" should compile
    "val x = c(123)" should compile // non-Unit value 123 is discarded, but it's only a warning

    "val x = a()" shouldNot compile
    "val x = f()" shouldNot compile
    "val x = f.timeout()(1 second)" shouldNot compile
    "val r = go { case f(_, r) => r() } " shouldNot compile
    "val r = go { case f(_, r) => r.checkTimeout() } " shouldNot compile
  }

  it should "support concise syntax for Unit-typed molecules" in {
    val a = new M[Unit]("a")
    val f = new B[Unit, Unit]("f")
    val h = b[Unit, Boolean]
    val g = b[Unit, Unit]
    a.name shouldEqual "a"
    f.name shouldEqual "f"
    g.name shouldEqual "g"
    // This should compile without any argument adaptation warnings:
    go { case a(_) + f(_, r) + g(_, s) + h(_, q) => a() + s() + f(); val status = r.checkTimeout(); q(status) }
  }

  it should "fail to compile a reaction with empty singleton clause" in {
    "val r = go { case _ => }" shouldNot compile
  }

  it should "fail to compile a guard that replies to molecules" in {
    val f = b[Unit, Unit]
    val x = 2
    x shouldEqual 2
    f.isInstanceOf[B[Unit, Unit]] shouldEqual true

    "val r = go { case f(_, r) if r() && x == 2 => }" shouldNot compile
  }

  it should "fail to compile a guard that emits molecules" in {
    val f = b[Unit, Boolean]
    f.isInstanceOf[B[Unit, Boolean]] shouldEqual true
    "val r = go { case f(_, r) if f() => r(true) }" shouldNot compile
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

  it should "fail to compile a reaction with regrouped inputs" in {
    val a = m[Unit]
    a.isInstanceOf[M[Unit]] shouldEqual true

    "val r = go { case a(_) + (a(_) + a(_)) => }" shouldNot compile
    "val r = go { case a(_) + (a(_) + a(_)) + a(_) => }" shouldNot compile
    "val r = go { case (a(_) + a(_)) + a(_) + a(_) => }" should compile
  }

  it should "fail to compile a reaction with grouped pattern variables in inputs" in {
    val a = m[Unit]
    a.name shouldEqual "a"

    "val r = go { case a(_) + x@(a(_) + a(_)) => }" shouldNot compile
    "val r = go { case a(_) + (a(_) + a(_)) + x@a(_) => }" shouldNot compile
    "val r = go { case x@a(_) + (a(_) + a(_)) + a(_) => }" shouldNot compile
    "val r = go { case x@(a(_) + a(_)) + a(_) + a(_) => }" shouldNot compile
    "val r = go { case x@a(_) => }" shouldNot compile
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

  behavior of "nonlinear output environments"

  it should "refuse emitting blocking molecules" in {
    val c = m[Unit]
    val f = b[Unit, Unit]
    val f2 = b[Int, Unit]
    val f3 = b[Unit, Boolean]
    val g: Any => Any = x => x

    c.name shouldEqual "c"
    f.name shouldEqual "f"
    f2.name shouldEqual "f2"
    f3.name shouldEqual "f3"
    g(()) shouldEqual (())

    go { case c(_) => g(f) }
    "val r = go { case c(_) => (0 until 10).flatMap { _ => f(); List() } }" shouldNot compile // reaction body must not emit blocking molecules inside function blocks
    "val r = go { case c(_) => (0 until 10).foreach(i => f2(i)) }" shouldNot compile // same
    "val r = go { case c(_) => (0 until 10).foreach(_ => c()) }" should compile // `c` is a non-blocking molecule, OK to emit it anywhere
    "val r = go { case c(_) => (0 until 10).foreach(f2) }" shouldNot compile // same
    "val r = go { case c(_) => (0 until 10).foreach{_ => g(f); () } }" shouldNot compile
    "val r = go { case c(_) => while (true) f() }" shouldNot compile
    "val r = go { case c(_) => while (true) c() }" should compile // `c` is a non-blocking molecule, OK to emit it anywhere
    "val r = go { case c(_) => while (f3()) { () } }" shouldNot compile
    "val r = go { case c(_) => do f() while (true) }" shouldNot compile
    "val r = go { case c(_) => do c() while (f3()) }" shouldNot compile
  }

  it should "refuse emitting blocking molecule replies" in {
    val f = b[Unit, Unit]
    val f2 = b[Unit, Int]
    val g: Any => Any = x => x

    f.name shouldEqual "f"
    f2.name shouldEqual "f2"
    g(()) shouldEqual (())

    "val r = go { case f(_, r) => g(r); r() }" shouldNot compile
    "val r = go { case f(_, r) => val x = r; g(x); r() }" shouldNot compile
    "val r = go { case f(_, r) => (0 until 10).flatMap { _ => r(); List() } }" shouldNot compile // reaction body must not emit blocking molecule replies inside function blocks
    "val r = go { case f2(_, r) => (0 until 10).foreach(i => r(i)) }" shouldNot compile // same
    "val r = go { case f2(_, r) => (0 until 10).foreach(r) }" shouldNot compile // same
    "val r = go { case f2(_, r) => (0 until 10).foreach(_ => g(r)); r(0) }" shouldNot compile
    "val r = go { case f(_, r) => while (true) r() }" shouldNot compile
    "val r = go { case f(_, r) => while (r.checkTimeout()) { () } }" shouldNot compile
    "val r = go { case f(_, r) => do r() while (true) }" shouldNot compile
    "val r = go { case f(_, r) => do () while (r.checkTimeout()) }" shouldNot compile
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
    "val r = go { case bb(x) =>  if (x > 0) bb(1) }" should compile // no unconditional livelock due to `if` in reaction
    "val r = go { case bb(x) =>  if (x > 0) bbb(1) else bb(2) }" should compile // no unconditional livelock due to `if` in reaction
    "val r = go { case bb(x) =>  if (x > 0) bb(1) else bb(2) }" shouldNot compile // unconditional livelock due to shrinkage of `if` in reaction
    "val r = go { case bbb(1) => bbb(2) }" should compile // no unconditional livelock
    "val r = go { case bb(x) => bb(1) }" shouldNot compile // unconditional livelock
    "val r = go { case a(_) => a((1,1)) }" shouldNot compile // unconditional livelock
    "val r = go { case bbb(_) => bbb(0) }" shouldNot compile // unconditional livelock
    "val r = go { case bbb(x) => bbb(x + 1) + bb(x) }" shouldNot compile
    "val r = go { case bbb(x) + bb(y) => bbb(x + 1) + bb(x) + bb(y + 1) }" shouldNot compile
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
      // However, this "crushing" is precisely what this test focuses on, and we cannot tell scalac to ignore this warning.
      case bb(_) + bb(z) if (z match {
        case (1, Some(x)) if x > 0 => true;
        case _ => false
      }) =>
    }

    (result.info.inputs match {
      case Array(
      InputMoleculeInfo(`bb`, 0, WildcardInput, _),
      InputMoleculeInfo(`bb`, 1, SimpleVarInput('z, Some(cond)), _)
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