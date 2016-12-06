package code.winitzki.benchmark

import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Macros._
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.time.{Millis, Span}

class JoinRunStaticAnalysisSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(500, Millis)

  val warmupTimeMs = 50

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

  behavior of "static reaction analysis"

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
    join(
      & { case a(1) => },
      & { case a(_) + b(_) => }
    ) shouldEqual ()
  }

}