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

    val tp1 = new FixedPool(1) // This test works only with single threads.

    join(tp1, tp1)(
      & {case f(_, r) + d(text) => r(text); d(text) },
      & {case _ => d("ok") } // singleton
    )

    (1 to 50).foreach { i =>
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

  behavior of "volatile reader"

  it should "read the initial value of the singleton molecule after stabilization" in {
    val d = m[Int]
    val incr = b[Unit, Int]
    val stabilize_d = b[Unit, Unit]

    val tp = new FixedPool(1)

    join(tp)(
      & { case d(x) + incr(_, r) => r(x); d(x+1) },
      & { case d(x) + stabilize_d(_, r) => r(); d(x) }, // Await stabilizing the presence of d
      & { case _ => d(123) } // singleton
    )
    stabilize_d()
    d.value shouldEqual 123

    tp.shutdownNow()
  }

  it should "read the value of the singleton molecule after many changes" in {
    val d = m[Int]
    val incr = b[Unit, Unit]
    val stabilize_d = b[Unit, Unit]

    val tp = new FixedPool(1)

    val n = 100
    val delta_n = 500

    join(tp)(
      & { case d(x) + incr(_, r) => d(x+1); r() },
      & { case d(x) + stabilize_d(_, r) => d(x); r() }, // Await stabilizing the presence of d
      & { case _ => d(n) } // singleton
    )
    stabilize_d()
    d.value shouldEqual n

    (n+1 to n+delta_n).foreach { i =>
      d.value shouldEqual i-1
      incr()
      d.value shouldEqual i
    }

    tp.shutdownNow()
  }

}
