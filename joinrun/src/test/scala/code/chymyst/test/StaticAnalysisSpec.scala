package code.chymyst.test

import code.chymyst.jc._
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.{Millis, Span}
import org.scalatest.{FlatSpec, Matchers}

class StaticAnalysisSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(1000, Millis)

  val warmupTimeMs = 50L

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

  behavior of "analysis of reaction shadowing"

  it should "detect shadowing of simplest reactions" in {
    val thrown = intercept[Exception] {
      val a = m[Unit]
      val b = m[Unit]
      site(
        go { case a(_) => b() },
        go { case a(_) => }
      )
    }
    thrown.getMessage shouldEqual "In Site{a => ...; a => ...}: Unavoidable nondeterminism: reaction {a(_) => } is shadowed by {a(_) => b()}, reaction {a(_) => b()} is shadowed by {a(_) => }"
  }

  it should "detect shadowing of reactions with wildcards" in {
    val thrown = intercept[Exception] {
      val a = m[Unit]
      val b = m[Unit]
      site(
        go { case a(_) => },
        go { case a(_) + b(_) => }
      )
    }
    thrown.getMessage shouldEqual "In Site{a + b => ...; a => ...}: Unavoidable nondeterminism: reaction {a(_) + b(_) => } is shadowed by {a(_) => }"
  }

  it should "detect shadowing of reactions with infallible matchers" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      val b = m[Int]
      site(
        go { case a(x) => },
        go { case a(1) + b(2) => }
      )
    }
    thrown.getMessage shouldEqual "In Site{a + b => ...; a => ...}: Unavoidable nondeterminism: reaction {a(1) + b(2) => } is shadowed by {a(x) => }"
  }

  it should "detect no shadowing of reactions with nontrivial matchers" in {
    val a = m[Int]
    val b = m[Unit]
    val result = site(
      go { case a(1) => },
      go { case a(_) + b(_) => }
    )
    result.hasErrorsOrWarnings shouldEqual false
  }

  it should "detect no shadowing of reactions with guards" in {
    val a = m[Int]
    val b = m[Unit]
    val result = site(
      go { case a(x) if x > 0 => },
      go { case a(_) + b(_) => }
    )
    result.hasErrorsOrWarnings shouldEqual false
  }

  it should "detect shadowing of reactions with identical constant matchers" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      val b = m[Int]
      site(
        go { case a(1) => },
        go { case a(1) + b(2) => }
      )
    }
    thrown.getMessage shouldEqual "In Site{a + b => ...; a => ...}: Unavoidable nondeterminism: reaction {a(1) + b(2) => } is shadowed by {a(1) => }"
  }

  it should "detect shadowing of reactions with identical non-constant matchers" in {
    val thrown = intercept[Exception] {
      val a = m[Option[Int]]
      val b = m[Int]
      site(
        go { case a(Some(1)) => },
        go { case a(Some(1)) + b(2) => }
      )
    }

    thrown.getMessage shouldEqual "In Site{a + b => ...; a => ...}: Unavoidable nondeterminism: reaction {a(Some(1)) + b(2) => } is shadowed by {a(Some(1)) => }"
  }

  it should "detect shadowing of reactions with non-identical complicated constant matchers" in {
    val thrown = intercept[Exception] {
      val a = m[Option[Int]]
      val b = m[Int]
      site(
        go { case a(Some(_)) => },
        go { case a(Some(1)) + b(2) => }
      )
    }
    thrown.getMessage shouldEqual "In Site{a + b => ...; a => ...}: Unavoidable nondeterminism: reaction {a(Some(1)) + b(2) => } is shadowed by {a(<12E5...>) => }"
  }

  it should "detect shadowing of reactions with non-identical matchers that match a constant and a wildcard" in {
    val thrown = intercept[Exception] {
      val a = m[Option[Int]]
      val b = m[Int]
      site(
        go { case b(_) + a(Some(1)) => },
        go { case a(Some(1)) + b(2) => }
      )
    }

    thrown.getMessage shouldEqual "In Site{a + b => ...; a + b => ...}: Unavoidable nondeterminism: reaction {a(Some(1)) + b(2) => } is shadowed by {a(Some(1)) + b(_) => }"
  }

  object IsEven {
    def unapply(x: Int): Option[Int] = if (x % 2 == 0) Some(x / 2) else None
  }

  it should "detect shadowing of reactions with non-identical matchers that are nontrivially weaker" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      val b = m[Int]
      site(
        go { case a(IsEven(x)) => },
        go { case a(2) + b(3) => }
      )
    }
    thrown.getMessage shouldEqual "In Site{a + b => ...; a => ...}: Unavoidable nondeterminism: reaction {a(2) + b(3) => } is shadowed by {a(<A854...>) => }"
  }

  it should "detect shadowing of reactions with non-identical matchers that are nontrivially not weaker" in {

    val a = m[Int]
    val b = m[Int]
    val result = site(
      go { case a(IsEven(x)) => },
      go { case a(1) + b(3) => }
    )
    result.hasErrorsOrWarnings shouldEqual false
  }

  it should "detect shadowing of reactions with all supported matcher combinations" in {
    val thrown = intercept[Exception] {
      val a = m[Option[Int]]
      val b = m[Int]
      site(
        go { case a(_) + b(1) + a(Some(2)) + a(x) + b(1) + b(_) => },
        go { case a(Some(1)) + b(2) + a(Some(2)) + a(Some(3)) + b(1) + b(_) + b(1) => }
      )
    }

    thrown.getMessage shouldEqual "In Site{a + a + a + b + b + b + b => ...; a + a + a + b + b + b => ...}: Unavoidable nondeterminism: reaction {a(Some(3)) + a(Some(2)) + a(Some(1)) + b(1) + b(1) + b(2) + b(_) => } is shadowed by {a(Some(2)) + a(_) + a(x) + b(1) + b(1) + b(_) => }"
  }

  it should "detect shadowing of reactions with several wildcards" in {
    val thrown = intercept[Exception] {
      val a = m[Option[Int]]
      val b = m[Int]
      site(
        go { case a(_) + b(1) + a(Some(2)) + a(x) + a(_) => },
        go { case a(Some(1)) + b(2) + a(Some(2)) + a(Some(3)) + b(1) + b(_) + b(1) + a(x) => }
      )
    }

    thrown.getMessage shouldEqual "In Site{a + a + a + a + b + b + b + b => ...; a + a + a + a + b => ...}: Unavoidable nondeterminism: reaction {a(Some(3)) + a(Some(2)) + a(Some(1)) + a(x) + b(1) + b(1) + b(2) + b(_) => } is shadowed by {a(Some(2)) + a(_) + a(_) + a(x) + b(1) => }"
  }

  behavior of "analysis of livelock"

  it should "detect livelock in a single reaction due to constant output values" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      val b = m[Int]

      site(go { case a(1) + b(_) => b(1) + b(2) + a(1) })

    }
    thrown.getMessage shouldEqual "In Site{a + b => ...}: Unavoidable livelock: reaction {a(1) + b(_) => b(1) + b(2) + a(1)}"
  }

  it should "not detect livelock in a single reaction due to different constant output values" in {
    val a = m[Int]
    val b = m[Int]
    val result = site(
      go { case a(1) + b(3) => b(1) + b(2) + a(1) }
    )
    result.hasErrorsOrWarnings shouldEqual false
  }

  it should "detect possible livelock in a single reaction due to guard" in {
    val a = m[Int]
    val result = site(
      go { case a(x) if x > 0 => a(x) }
    )
    result shouldEqual WarningsAndErrors(List("Possible livelock: reaction {a(x if ?) => a(?)}"), List(), "Site{a => ...}")
  }

  it should "detect possible livelock in a single reaction due to nontrivial matchers" in {
    val a = m[Int]
    val result = site(
      go { case a(IsEven(x)) => a(x) }
    )
    result shouldEqual WarningsAndErrors(List("Possible livelock: reaction {a(<A854...>) => a(?)}"), List(), "Site{a => ...}")
  }

  it should "detect livelock in a single reaction due to constant output values with nontrivial matchers" in {
    val thrown = intercept[Exception] {
      val a = m[Option[Int]]
      val b = m[Int]
      val c = m[Int]

      site(
        go { case b(IsEven(x)) + b(_) + a(_) + c(1) => c(1) + b(1) + b(2) + a(Some(1)) + c(2) }
      )

    }
    thrown.getMessage shouldEqual "In Site{a + b + b + c => ...}: Unavoidable livelock: reaction {a(_) + b(<A854...>) + b(_) + c(1) => c(1) + b(1) + b(2) + a(Some(1)) + c(2)}"
  }

  it should "detect livelock in a simple reaction due to constant output values" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      site(
        go { case a(1) => a(1) }
      )
    }
    thrown.getMessage shouldEqual "In Site{a => ...}: Unavoidable livelock: reaction {a(1) => a(1)}"
  }

  it should "detect livelock in a single reaction due to constant output values without value assigning" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      val b = m[Int]
      site(
        go { case a(1) + b(_) => b(1) + b(2) + a(1) }
      )
    }
    thrown.getMessage shouldEqual "In Site{a + b => ...}: Unavoidable livelock: reaction {a(1) + b(_) => b(1) + b(2) + a(1)}"
  }

  it should "detect livelock in a single reaction due to one constant output value with simple guard" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      val b = m[Int]
      val warnings = site(
        go { case a(1) + b(x) if x > 0 => b(1) + b(2) + a(1) }
      )
      // When static analysis fails to produce an error, it might give this warning. If this test fails due to no exception thrown, we know that this warning was produced.
      warnings shouldEqual WarningsAndErrors(List("Possible livelock: reaction {a(1) + b(x if ?) => b(1) + b(2) + a(1)}"), List(), "Site{a + b => ...}")
    }
    thrown.getMessage shouldEqual "In Site{a + b => ...}: Unavoidable livelock: reaction {a(1) + b(x if ?) => b(1) + b(2) + a(1)}"
  }

  it should "detect livelock in a single reaction due to two constant output values with simple guard" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      val b = m[Int]
      val warnings = site(
        go { case a(1) + b(x) if x > 1 => b(1) + b(2) + a(1) }
      )
      // When static analysis fails to produce an error, it might give this warning. If this test fails due to no exception thrown, we know that this warning was produced.
      warnings shouldEqual WarningsAndErrors(List("Possible livelock: reaction {a(1) + b(x if ?) => b(1) + b(2) + a(1)}"), List(), "Site{a + b => ...}")
    }
    thrown.getMessage shouldEqual "In Site{a + b => ...}: Unavoidable livelock: reaction {a(1) + b(x if ?) => b(1) + b(2) + a(1)}"
  }

  it should "detect livelock in a single reaction due to nontrivial matcher with simple guard" in {
    val thrown = intercept[Exception] {
      val a = m[Option[Int]]
      val warnings = site(
        go { case a(Some(x)) if x > 1 => a(Some(2)) }
      )
      // When static analysis fails to produce an error, it might give this warning. If this test fails due to no exception thrown, we know that this warning was produced.
      warnings shouldEqual WarningsAndErrors(List("Possible livelock: reaction {a(<9247...>) => a(?)}"), List(), "Site{a => ...}")
    }
    thrown.getMessage shouldEqual "In Site{a => ...}: Unavoidable livelock: reaction {a(<9247...>) => a(Some(2))}"
  }

  it should "give a livelock warning in a single reaction due to constant output values" in {
    val p = m[Int]
    val q = m[Int]
    val warnings = site(
      go { case p(x) + q(1) => q(x) + q(2) + p(1) } // Will have livelock when x == 1, but not otherwise, thus it is a warning and not an error.
    )

    warnings shouldEqual WarningsAndErrors(List("Possible livelock: reaction {p(x) + q(1) => q(?) + q(2) + p(1)}"), List(), "Site{p + q => ...}")
  }

  it should "give a livelock warning in a single reaction due to constant output values with simple guard" in {
    val p = m[Int]
    val q = m[Int]
    val warnings = site(
      go { case p(x) + q(1) if x > 0 => q(x) + q(2) + p(1) } // The condition x > 0 is true when x = 1.
    )

    warnings shouldEqual WarningsAndErrors(List("Possible livelock: reaction {p(x if ?) + q(1) => q(?) + q(2) + p(1)}"), List(), "Site{p + q => ...}")
  }

  it should "detect shadowing together with livelock" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      val b = m[Int]

      site(
        go { case a(1) + b(_) => b(1) + b(2) + a(1) },
        go { case a(IsEven(x)) => a(2) },
        go { case a(2) + b(3) => }
      )
    }
    thrown.getMessage shouldEqual "In Site{a + b => ...; a + b => ...; a => ...}: Unavoidable nondeterminism: reaction {a(2) + b(3) => } is shadowed by {a(<A854...>) => a(2)}; Unavoidable livelock: reactions {a(1) + b(_) => b(1) + b(2) + a(1)}, {a(<A854...>) => a(2)}"
  }

  behavior of "deadlock detection"

  it should "not warn about likely deadlock for a reaction that emits molecules for itself in the right order" in {
    val a = m[Int]
    val c = m[Int]
    val f = b[Unit, Int]

    val warnings = site(
      go { case f(_, r) + a(_) + c(_) => r(0); a(1); f() }
    )
    warnings shouldEqual WarningsAndErrors(Nil, Nil, "Site{a + c + f/B => ...}")
  }

  it should "warn about likely deadlock for a reaction that emits molecules for itself" in {
    val a = m[Int]
    val c = m[Int]
    val f = b[Unit, Int]

    val warnings = site(
      go { case f(_, r) + a(_) + c(_) => f(); r(0); a(1) }
    )
    warnings shouldEqual WarningsAndErrors(List("Possible deadlock: molecule f/B may deadlock due to outputs of {a(_) + c(_) + f/B(_) => f/B() + a(1)}", "Possible deadlock: molecule (f/B) may deadlock due to (a) among the outputs of {a(_) + c(_) + f/B(_) => f/B() + a(1)}"), List(), "Site{a + c + f/B => ...}")
  }

  it should "warn about likely deadlock for a reaction that emits molecules for another reaction" in {
    val a = m[Int]
    val c = m[Int]
    val f = b[Unit, Int]

    val warnings1 = site(
      go { case f(_, r) + a(_) => r(0); a(1) }
    )

    val warnings2 = site(
      go { case c(_) => f(); a(1) }
    )
    warnings1 shouldEqual WarningsAndErrors(Nil, Nil, "Site{a + f/B => ...}")
    warnings2 shouldEqual WarningsAndErrors(List("Possible deadlock: molecule f/B may deadlock due to outputs of {a(_) + f/B(_) => a(1)}"), List(), "Site{c => ...}")
  }

  behavior of "repeated reaction detection"

  it should "detect a repeated reaction as a warning in the presence of other errors" in {
    val a = m[Int]
    val c = m[Unit]
    val f = b[Unit, Int]

    val thrown = intercept[Exception] {
      val warnings = site(
        go { case c(_) + a(x) => a(x) },
        go { case c(_) + a(x) => a(x) },
        go { case a(x) + f(_, r) => r(x) },
        go { case a(x) + f(_, r) => r(x) }
      )
      warnings shouldEqual WarningsAndErrors(List("Identical repeated reactions: {a(x) + c(_) => a(?)}, {a(x) + f/B(_) => }"), List(), "Site{a + c => ...; a + c => ...; a + f/B => ...; a + f/B => ...}") // this is probably unreachable; later we could rewrite this test when logging is better handled
    }
    thrown.getMessage shouldEqual "In Site{a + c => ...; a + c => ...; a + f/B => ...; a + f/B => ...}: Unavoidable nondeterminism: reaction {a(x) + c(_) => a(?)} is shadowed by {a(x) + c(_) => a(?)}, reaction {a(x) + c(_) => a(?)} is shadowed by {a(x) + c(_) => a(?)}, reaction {a(x) + f/B(_) => } is shadowed by {a(x) + f/B(_) => }, reaction {a(x) + f/B(_) => } is shadowed by {a(x) + f/B(_) => }"
  }

  it should "detect several repeated reactions" in {

    val a1 = m[Int]
    val a2 = m[Int]
    val a3 = m[Int]
    val c1 = m[Unit]
    val c2 = m[Unit]
    val c3 = m[Unit]
    val f = b[Unit, Int]

    val reaction1 = {
      val a = a1
      val c = c1
      go { case a(x) + c(_) => a(x) }
    }

    val reaction2 = {
      val a = a2
      val c = c2
      go { case a(x) + c(_) => a(x) }
    }

    val reaction3 = {
      val a = a3
      val c = c3
      go { case a(x) + c(_) => a(x) }
    }

    val reaction4 = {
      val c = c1
      go { case f(_, r) + c(_) => r(0) }
    }

    val reaction5 = {
      val c = c2
      go { case f(_, r) + c(_) => r(0) }
    }

    val warnings = site(reaction1, reaction2, reaction3, reaction4, reaction5)

    warnings shouldEqual WarningsAndErrors(List("Identical repeated reactions: {a1(x) + c1(_) => a1(?)}, {c1(_) + f/B(_) => }"), List(), "Site{a1 + c1 => ...; a2 + c2 => ...; a3 + c3 => ...; c1 + f/B => ...; c2 + f/B => ...}")
  }

}
