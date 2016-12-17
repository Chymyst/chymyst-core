package code.winitzki.test

import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Macros._
import code.winitzki.jc.{FixedPool, SmartPool}
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

    (1 to 20).foreach { i =>
      Thread.sleep(20)
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

  it should "refuse to read the value of a molecule not bound to a join definition" in {
    val c = m[Int]

    val thrown = intercept[Exception] {
      c.volatileValue
    }

    thrown.getMessage shouldEqual "Molecule c is not bound to any join definition"
  }

  it should "refuse to read the value of a non-singleton molecule" in {
    val c = m[Int]

    val tp = new FixedPool(1)
    join(tp)( & { case c(_) => } )

    val thrown = intercept[Exception] {
      c.volatileValue
    }

    thrown.getMessage shouldEqual "In Join{c => ...}: volatile reader requested for non-singleton (c)"
    tp.shutdownNow()
  }

  it should "refuse to read the value of a singleton too early" in {

    val tp = new FixedPool(1)

    def makeNewVolatile(i: Int) = {
      val c = m[Int]
      val d = m[Int]

      join(tp)(
        & { case c(x) + d(_) => d(x) },
        & { case _ => d(i) }
      )

      d.volatileValue
    }


    val thrown = intercept[Exception] {
      (1 to 100).foreach { i =>
        makeNewVolatile(i) // This should sometimes throw an exception, so let's make sure it does.
      }
    }

    thrown.getMessage shouldEqual "The volatile reader for singleton (d) is not yet ready"

    tp.shutdownNow()
  }

  it should "report that the value of a singleton is not ready if called too early" in {

    val tp = new FixedPool(1)

    def makeNewVolatile(i: Int): Int = {
      val c = m[Int]
      val d = m[Int]

      join(tp)(
        & { case c(x) + d(_) => d(x) },
        & { case _ => d(i) }
      )

      if (d.hasVolatileValue) 0 else 1
    }

    val result = (1 to 100).map { i =>
      makeNewVolatile(i)
    }.sum // how many times we failed

    println(s"Volatile value was not ready $result times")
    result should be > 50

    tp.shutdownNow()
  }

  it should "read the initial value of the singleton molecule after stabilization" in {
    val d = m[Int]
    val stabilize_d = b[Unit, Unit]

    val tp = new FixedPool(1)

    join(tp)(
      & { case d(x) + stabilize_d(_, r) => r(); d(x) }, // Await stabilizing the presence of d
      & { case _ => d(123) } // singleton
    )
    stabilize_d()
    d.hasVolatileValue shouldEqual true
    d.volatileValue shouldEqual 123

    tp.shutdownNow()
  }

  it should "read the value of the singleton molecule sometimes inaccurately after many changes" in {
    val d = m[Int]
    val incr = b[Unit, Unit]
    val stabilize_d = b[Unit, Unit]

    val tp = new FixedPool(1)

    val n = 1
    val delta_n = 1000

    join(tp)(
      & { case d(x) + incr(_, r) => r(); d(x+1) },
      & { case d(x) + stabilize_d(_, r) => d(x); r() }, // Await stabilizing the presence of d
      & { case _ => d(n) } // singleton
    )
    stabilize_d()
    d.volatileValue shouldEqual n

    (n+1 to n+delta_n).map { i =>
      incr()
      i - d.volatileValue // this is mostly 0 but sometimes 1
    }.sum should be > 0 // there should be some cases when d.value reads the previous value

    tp.shutdownNow()
  }

  it should "keep the previous value of the singleton molecule while update reaction is running" in {
    val d = m[Int]
    val e = m[Unit]
    val wait = b[Unit, Unit]
    val incr = b[Unit, Unit]
    val stabilize_d = b[Unit, Unit]

    val tp1 = new FixedPool(1)
    val tp3 = new SmartPool(5)

    join(tp3)(
      & { case wait(_, r) + e(_) => r() } onThreads tp3,
      & { case d(x) + incr(_, r) => r(); wait(); d(x+1) } onThreads tp1,
      & { case d(x) + stabilize_d(_, r) => d(x); r() } onThreads tp1, // Await stabilizing the presence of d
      & { case _ => d(100) } // singleton
    )
    stabilize_d()
    d.volatileValue shouldEqual 100
    incr() // update started and is waiting for e()
    d.volatileValue shouldEqual 100 // We don't have d() present in the soup, but we can read its previous value.
    e()
    stabilize_d()
    d.volatileValue shouldEqual 101

    tp1.shutdownNow()
    tp3.shutdownNow()
  }

}
