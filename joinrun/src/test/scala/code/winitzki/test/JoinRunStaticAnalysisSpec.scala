package code.winitzki.test

import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Macros._
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.{Millis, Span}
import org.scalatest.{FlatSpec, Matchers}

class JoinRunStaticAnalysisSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(500, Millis)

  val warmupTimeMs = 50

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

  behavior of "analysis of reaction shadowing"

  it should "detect shadowing of reactions with wildcards" in {
    val thrown = intercept[Exception] {
      val a = m[Unit]
      val b = m[Unit]
      join(
        & { case a(_) => },
        & { case a(_) + b(_) => }
      )
    }
    thrown.getMessage shouldEqual "In Join{a + b => ...; a => ...}: Unavoidable indeterminism: reaction a + b => ... is shadowed by a => ..."
  }

  it should "detect shadowing of reactions with unfallible matchers" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      val b = m[Int]
      join(
        & { case a(x) => },
        & { case a(1) + b(2) => }
      )
    }
    thrown.getMessage shouldEqual "In Join{a + b => ...; a => ...}: Unavoidable indeterminism: reaction a + b => ... is shadowed by a => ..."
  }

  it should "detect no shadowing of reactions with nontrivial matchers" in {
    val a = m[Int]
    val b = m[Unit]
    val result = join(
      & { case a(1) => },
      & { case a(_) + b(_) => }
    )
    result shouldEqual ()
  }

  it should "detect no shadowing of reactions with guards" in {
    val a = m[Int]
    val b = m[Unit]
    val result = join(
      & { case a(x) if x > 0 => },
      & { case a(_) + b(_) => }
    )
    result shouldEqual ()
  }

  it should "detect shadowing of reactions with identical constant matchers" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      val b = m[Int]
      join(
        & { case a(1) => },
        & { case a(1) + b(2) => }
      )
    }
    thrown.getMessage shouldEqual "In Join{a + b => ...; a => ...}: Unavoidable indeterminism: reaction a + b => ... is shadowed by a => ..."
  }

  it should "detect shadowing of reactions with identical non-constant matchers" in {
    val thrown = intercept[Exception] {
      val a = m[Option[Int]]
      val b = m[Int]
      join(
        & { case a(Some(1)) => },
        & { case a(Some(1)) + b(2) => }
      )
    }
    thrown.getMessage shouldEqual "In Join{a + b => ...; a => ...}: Unavoidable indeterminism: reaction a + b => ... is shadowed by a => ..."
  }

  it should "fail to detect shadowing of reactions with non-identical non-constant matchers" in {
    val a = m[Option[Int]]
    val b = m[Int]
    val result = join(
      & { case a(Some(_)) => },
      & { case a(Some(1)) + b(2) => }
    )
    result shouldEqual()
  }

  it should "detect shadowing of reactions with non-identical matchers that match a constant and a wildcard" in {
    val thrown = intercept[Exception] {
      val a = m[Option[Int]]
      val b = m[Int]
      join(
        & { case b(_) + a(Some(1)) => },
        & { case a(Some(1)) + b(2) => }
      )
    }
    thrown.getMessage shouldEqual "In Join{a + b => ...; a + b => ...}: Unavoidable indeterminism: reaction a + b => ... is shadowed by a + b => ..."
  }

  object IsEven {
    def unapply(x: Int): Option[Int] = if (x % 2 == 0) Some(x/2) else None
  }

  it should "detect shadowing of reactions with non-identical matchers that are nontrivially weaker" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      val b = m[Int]
      join(
        & { case a(IsEven(x)) => },
        & { case a(2) + b(3) => }
      )
    }
    thrown.getMessage shouldEqual "In Join{a + b => ...; a => ...}: Unavoidable indeterminism: reaction a + b => ... is shadowed by a => ..."
  }

  it should "detect shadowing of reactions with non-identical matchers that are nontrivially not weaker" in {

    val a = m[Int]
    val b = m[Int]
    val result = join(
      & { case a(IsEven(x)) => },
      & { case a(1) + b(3) => }
    )
    result shouldEqual()
  }

  it should "detect shadowing of reactions with all supported matcher combinations" in {
    val thrown = intercept[Exception] {
      val a = m[Option[Int]]
      val b = m[Int]
      join(
        & { case a(_) + b(1) + a(Some(2)) + a(x) + b(1) + b(_) => },
        & { case a(Some(1)) + b(2) + a(Some(2)) + a(Some(3)) + b(1) + b(_) + b(1) => }
      )
    }
    thrown.getMessage shouldEqual "In Join{a + a + a + b + b + b + b => ...; a + a + a + b + b + b => ...}: Unavoidable indeterminism: reaction a + a + a + b + b + b + b => ... is shadowed by a + a + a + b + b + b => ..."
  }

  it should "detect shadowing of reactions with several wildcards" in {
    val thrown = intercept[Exception] {
      val a = m[Option[Int]]
      val b = m[Int]
      join(
        & { case a(_) + b(1) + a(Some(2)) + a(x) + a(_) => },
        & { case a(Some(1)) + b(2) + a(Some(2)) + a(Some(3)) + b(1) + b(_) + b(1) + a(x) => }
      )
    }
    thrown.getMessage shouldEqual "In Join{a + a + a + a + b + b + b + b => ...; a + a + a + a + b => ...}: Unavoidable indeterminism: reaction a + a + a + a + b + b + b + b => ... is shadowed by a + a + a + a + b => ..."
  }

  behavior of "analysis of livelock"

  it should "detect livelock in a single reaction due to constant output values" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      val b = m[Int]

      join(& { case a(1) + b(_) => b(1) + b(2) + a(1) })

    }
    thrown.getMessage shouldEqual "In Join{a + b => ...}: Unavoidable livelock: reaction a + b => ..."
  }

  it should "not detect livelock in a single reaction due to different constant output values" in {
    val a = m[Int]
    val b = m[Int]
    val result = join(
      & { case a(1) + b(3) => b(1) + b(2) + a(1) }
    )
    result shouldEqual()
  }

  it should "not detect livelock in a single reaction due to nontrivial matchers" in {
    val a = m[Int]
    val result = join(
      & { case a(IsEven(x)) => a(x) }
    )
    result shouldEqual()
  }

  it should "not detect livelock in a single reaction due to guard" in {
    val a = m[Int]
    val result = join(
      & { case a(x) if x > 0 => a(x) }
    )
    result shouldEqual()
  }

  it should "detect livelock in a single reaction due to constant output values with nontrivial matchers" in {
    val thrown = intercept[Exception] {
      val a = m[Option[Int]]
      val b = m[Int]
      val c = m[Int]

      join(
        & { case b(IsEven(x)) + b(_) + a(_) + c(1) => c(1) + b(1) + b(2) + a(Some(1)) + c(2) }
      )

    }
    thrown.getMessage shouldEqual "In Join{a + b + b + c => ...}: Unavoidable livelock: reaction a + b + b + c => ..."
  }

  it should "detect livelock in a simple reaction due to constant output values" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      join(
        & { case a(1) => a(1) }
      )
    }
    thrown.getMessage shouldEqual "In Join{a => ...}: Unavoidable livelock: reaction a => ..."
  }

  it should "detect livelock in a single reaction due to constant output values without value assigning" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      val b = m[Int]
      join(
        & { case a(1) + b(_) => b(1) + b(2) + a(1) }
      )
    }
    thrown.getMessage shouldEqual "In Join{a + b => ...}: Unavoidable livelock: reaction a + b => ..."
  }

  it should "detect shadowing together with livelock" in {
    val thrown = intercept[Exception] {
      val a = m[Int]
      val b = m[Int]

      join(
        & { case a(1) + b(_) => b(1) + b(2) + a(1) },
        & { case a(IsEven(x)) => a(2) },
        & { case a(2) + b(3) => }
      )
    }
    thrown.getMessage shouldEqual "In Join{a + b => ...; a + b => ...; a => ...}: Unavoidable indeterminism: reaction a + b => ... is shadowed by a => ...; Unavoidable livelock: reactions a + b => ..., a => ..."
  }


}
