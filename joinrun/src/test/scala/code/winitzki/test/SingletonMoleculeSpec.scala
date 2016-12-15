package code.winitzki.test

import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Macros._
import code.winitzki.jc.FixedPool
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.{Millis, Span}
import org.scalatest.{FlatSpec, Matchers}

class SingletonMoleculeSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(1500, Millis)

  it should "refuse to inject a singleton from user code" in {

    val f = b[Unit, String]
    val d = m[String]

    val tp1 = new FixedPool(2)

    join(tp1, tp1)(
      & {case f(_, r) + d(text) => r(text); d(text) },
      & {case _ => d("ok") } // singleton
    )

    (1 to 20).foreach { i =>
      d(s"bad $i") // this should not be injected
      f() shouldEqual "ok"
    }

    tp1.shutdownNow()
  }

  it should "signal error when a singleton is consumed by reaction but not injected" in {

    val tp = new FixedPool(3)

    val thrown = intercept[Exception] {
      val c = b[Unit, String]
      val d = m[Unit]

      join(tp)(
        & { case c(_, r) + d(_) => r("ok") },
        & { case _ => d() } // singleton
      )
    }
    thrown.getMessage shouldEqual "In Join{c/B + d => ...}: Incorrect chemistry: singleton (d) consumed but not injected by reaction c/B(_) + d(_) => "

    tp.shutdownNow()
  }

  it should "signal error when a singleton is consumed by reaction and injected twice" in {

    val tp = new FixedPool(3)

    val thrown = intercept[Exception] {
      val c = b[Unit, String]
      val d = m[Unit]

      join(tp)(
        & { case c(_, r) + d(_) => r("ok"); d() + d() },
        & { case _ => d() } // singleton
      )
    }
    thrown.getMessage shouldEqual "In Join{c/B + d => ...}: Incorrect chemistry: singleton (d) injected more than once by reaction c/B(_) + d(_) => d() + d()"

    tp.shutdownNow()
  }

  it should "signal error when a singleton is injected but not consumed by reaction" in {

    val tp = new FixedPool(3)

    val thrown = intercept[Exception] {
      val c = b[Unit, String]
      val d = m[Unit]
      val e = m[Unit]

      join(tp)(
        & { case c(_, r) => r("ok"); d() },
        & { case e(_) => d() },
        & { case _ => d() } // singleton
      )
    }
    thrown.getMessage shouldEqual "In Join{c/B => ...; e => ...}: Incorrect chemistry: singleton (d) injected but not consumed by reaction c/B(_) => d(); singleton (d) injected but not consumed by reaction e(_) => d(); Incorrect chemistry: singleton (d) not consumed by any reactions"

    tp.shutdownNow()
  }

  it should "signal error when a singleton is injected but not bound to any join definition" in {

    val tp = new FixedPool(3)

    val thrown = intercept[Exception] {
      val d = m[Unit]

      join(tp)(
        & { case _ => d() } // singleton
      )
    }
    thrown.getMessage shouldEqual "Molecule d is not bound to any join definition"

    tp.shutdownNow()
  }

  it should "signal error when a singleton is injected but not bound to this join definition" in {

    val tp = new FixedPool(3)

    val thrown = intercept[Exception] {
      val c = b[Unit, String]
      val d = m[Unit]

      join(tp)(
        & { case d(_) => }
      )

      join(tp)(
        & { case c(_, r) => r("ok"); d() },
        & { case _ => d() } // singleton
      )
    }
    thrown.getMessage shouldEqual "In Join{c/B => ...}: Incorrect chemistry: singleton (d) injected but not consumed by reaction c/B(_) => d(); Incorrect chemistry: singleton (d) not consumed by any reactions"

    tp.shutdownNow()
  }

}
