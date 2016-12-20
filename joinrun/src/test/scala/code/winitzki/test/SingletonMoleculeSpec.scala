package code.winitzki.test

import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Macros.{run => &}
import code.winitzki.jc.Macros._
import code.winitzki.jc.{FixedPool, SmartPool}
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.{Millis, Span}
import org.scalatest.{FlatSpec, Matchers}
import scala.language.postfixOps

import scala.concurrent.duration._

class SingletonMoleculeSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(3000, Millis)

  behavior of "singleton injection"

  it should "refuse to inject a singleton from user code" in {

    val f = b[Unit, String]
    val d = m[String]

    val tp1 = new FixedPool(1) // This test works only with single threads.

    site(tp1, tp1)(
      & {case f(_, r) + d(text) => r(text); d(text) },
      & {case _ => d("ok") } // singleton
    )

    (1 to 100).foreach { i =>
      d(s"bad $i") // this "d" should not be injected, even though "d" is sometimes not in the soup due to reactions!
//      f(timeout = 200 millis)() shouldEqual Some("ok")
      f()
    }

    tp1.shutdownNow()
  }

  it should "refuse to inject a singleton immediately after reaction site" in {

    val tp1 = new FixedPool(1) // This test works only with single threads.

    (1 to 20).foreach { i =>
      val f = b[Unit, String]
      val d = m[String]

      site(tp1, tp1)(
        & {case f(_, r) + d(text) => r(text); d(text) },
        & {case _ => d("ok") } // singleton
      )

      (1 to 10).foreach { j =>
        d(s"bad $i $j") // this "d" should not be injected, even though we are immediately after a reaction site,
        // and even if the initial d() injection was done late
        f(timeout = 200 millis)() shouldEqual Some("ok")
      }

    }

    tp1.shutdownNow()
  }

  it should "signal error when a singleton is consumed by reaction but not injected" in {

    val tp = new FixedPool(3)

    val thrown = intercept[Exception] {
      val c = b[Unit, String]
      val d = m[Unit]

      site(tp)(
        & { case c(_, r) + d(_) => r("ok") },
        & { case _ => d() } // singleton
      )
    }
    thrown.getMessage shouldEqual "In Site{c/B + d => ...}: Incorrect chemistry: singleton (d) consumed but not injected by reaction c/B(_) + d(_) => "

    tp.shutdownNow()
  }

  it should "signal error when a singleton is consumed by reaction and injected twice" in {

    val tp = new FixedPool(3)

    val thrown = intercept[Exception] {
      val c = b[Unit, String]
      val d = m[Unit]

      site(tp)(
        & { case c(_, r) + d(_) => r("ok"); d() + d() },
        & { case _ => d() } // singleton
      )
    }
    thrown.getMessage shouldEqual "In Site{c/B + d => ...}: Incorrect chemistry: singleton (d) injected more than once by reaction c/B(_) + d(_) => d() + d()"

    tp.shutdownNow()
  }

  it should "signal error when a singleton is injected but not consumed by reaction" in {

    val tp = new FixedPool(3)

    val thrown = intercept[Exception] {
      val c = b[Unit, String]
      val d = m[Unit]
      val e = m[Unit]

      site(tp)(
        & { case c(_, r) => r("ok"); d() },
        & { case e(_) => d() },
        & { case _ => d() } // singleton
      )
    }
    thrown.getMessage shouldEqual "In Site{c/B => ...; e => ...}: Incorrect chemistry: singleton (d) injected but not consumed by reaction c/B(_) => d(); singleton (d) injected but not consumed by reaction e(_) => d(); Incorrect chemistry: singleton (d) not consumed by any reactions"

    tp.shutdownNow()
  }

  it should "signal error when a singleton is consumed multiple times by reaction" in {

    val tp = new FixedPool(3)

    val thrown = intercept[Exception] {
      val d = m[Unit]
      val e = m[Unit]

      site(tp)(
        & { case e(_) + d(_) + d(_) => d() },
        & { case _ => d() } // singleton
      )
    }
    thrown.getMessage shouldEqual "In Site{d + d + e => ...}: Incorrect chemistry: singleton (d) consumed 2 times by reaction d(_) + d(_) + e(_) => d()"

    tp.shutdownNow()
  }

  it should "signal error when a singleton is injected but not bound to any reaction site" in {

    val tp = new FixedPool(3)

    val thrown = intercept[Exception] {
      val d = m[Unit]

      site(tp)(
        & { case _ => d() } // singleton
      )
    }
    thrown.getMessage shouldEqual "Molecule d is not bound to any reaction site"

    tp.shutdownNow()
  }

  it should "signal error when a singleton is injected but not bound to this reaction site" in {

    val tp = new FixedPool(3)

    val thrown = intercept[Exception] {
      val c = b[Unit, String]
      val d = m[Unit]

      site(tp)(
        & { case d(_) => }
      )

      site(tp)(
        & { case c(_, r) => r("ok"); d() },
        & { case _ => d() } // singleton
      )
    }
    thrown.getMessage shouldEqual "In Site{c/B => ...}: Incorrect chemistry: singleton (d) injected but not consumed by reaction c/B(_) => d(); Incorrect chemistry: singleton (d) not consumed by any reactions"

    tp.shutdownNow()
  }

  behavior of "volatile reader"

  it should "refuse to read the value of a molecule not bound to a reaction site" in {
    val c = m[Int]

    val thrown = intercept[Exception] {
      c.volatileValue
    }

    thrown.getMessage shouldEqual "Molecule c is not bound to any reaction site"
  }

  it should "refuse to read the value of a non-singleton molecule" in {
    val c = m[Int]

    val tp = new FixedPool(1)
    site(tp)( & { case c(_) => } )

    val thrown = intercept[Exception] {
      c.volatileValue
    }

    thrown.getMessage shouldEqual "In Site{c => ...}: volatile reader requested for non-singleton (c)"
    tp.shutdownNow()
  }

  it should "always be able to read the value of a singleton early" in {

    val tp = new FixedPool(1)

    def makeNewVolatile(i: Int) = {
      val c = m[Int]
      val d = m[Int]

      site(tp)(
        & { case c(x) + d(_) => d(x) },
        & { case _ => d(i) }
      )

      d.volatileValue
    }


    (1 to 100).foreach { i =>
      makeNewVolatile(i) // This should sometimes throw an exception, so let's make sure it does.
    }

    tp.shutdownNow()
  }

  it should "refuse to define a blocking molecule as a singleton" in {

    val tp = new FixedPool(1)

    val c = m[Int]
    val d = m[Int]
    val f = b[Unit, Unit]

    val thrown = intercept[Exception] {
      site(tp)(
        & { case f(_, r) => r() },
        & { case c(x) + d(_) => d(x) },
        & { case _ => f(); d(0) }
      )
    }

    thrown.getMessage shouldEqual "In Site{c + d => ...; f/B => ...}: Refusing to inject molecule f/B() as a singleton (must be a non-blocking molecule)"

    tp.shutdownNow()
  }

  it should "report that the value of a singleton is ready even if called early" in {

    val tp = new FixedPool(1)

    def makeNewVolatile(i: Int): Int = {
      val c = m[Int]
      val d = m[Int]

      site(tp)(
        & { case c(x) + d(_) => d(x) },
        & { case _ => d(i) }
      )

      if (d.hasVolatileValue) 0 else 1
    }

    val result = (1 to 100).map { i =>
      makeNewVolatile(i)
    }.sum // how many times we failed

    println(s"Volatile value was not ready $result times")
    result shouldEqual 0

    tp.shutdownNow()
  }

  it should "read the initial value of the singleton molecule after stabilization" in {
    val d = m[Int]
    val stabilize_d = b[Unit, Unit]

    val tp = new FixedPool(1)

    site(tp)(
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

    site(tp)(
      & { case d(x) + incr(_, r) => r(); d(x+1) },
      & { case d(x) + stabilize_d(_, r) => d(x); r() }, // Await stabilizing the presence of d
      & { case _ => d(n) } // singleton
    )
    stabilize_d(timeout = 500 millis)()
    d.volatileValue shouldEqual n

    (n+1 to n+delta_n).map { i =>
      incr(timeout = 500 millis)() shouldEqual Some(())

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

    site(tp3)(
      & { case wait(_, r) + e(_) => r() } onThreads tp3,
      & { case d(x) + incr(_, r) => r(); wait(); d(x+1) } onThreads tp1,
      & { case d(x) + stabilize_d(_, r) => d(x); r() } onThreads tp1, // Await stabilizing the presence of d
      & { case _ => d(100) } // singleton
    )
    stabilize_d(timeout = 500 millis)() shouldEqual Some(())
    d.volatileValue shouldEqual 100
    incr(timeout = 500 millis)() shouldEqual Some(()) // update started and is waiting for e()
    d.volatileValue shouldEqual 100 // We don't have d() present in the soup, but we can read its previous value.
    e()
    stabilize_d(timeout = 500 millis)() shouldEqual Some(())
    d.volatileValue shouldEqual 101

    tp1.shutdownNow()
    tp3.shutdownNow()
  }

  it should "signal error when a singleton is injected fewer times than declared" in {

    val tp = new FixedPool(3)

    val thrown = intercept[Exception] {
      val c = b[Unit, String]
      val d = m[Unit]
      val e = m[Unit]
      val f = m[Unit]

      site(tp)(
        & { case d(_) +e(_) + f(_) + c(_, r) => r("ok"); d(); e(); f() },
        & { case _ => if (false) { d(); e() }; f(); } // singletons d() and e() will actually not be injected because of a condition
      )
    }
    thrown.getMessage shouldEqual "In Site{c/B + d + e + f => ...}: Too few singletons injected: d injected 0 times instead of 1, e injected 0 times instead of 1"

    tp.shutdownNow()
  }

  it should "signal no error (but a warning) when a singleton is injected more times than declared" in {

    val tp = new FixedPool(3)

    val c = b[Unit, String]
    val d = m[Unit]
    val e = m[Unit]
    val f = m[Unit]

    val warnings = site(tp)(
      & { case d(_) + e(_) + f(_) + c(_, r) => r("ok"); d(); e(); f() },
      & { case _ => (1 to 2).foreach { _ => d(); e() }; f(); } // singletons d() and e() will actually be injected more times
    )

    warnings.errors shouldEqual Seq()
    warnings.warnings shouldEqual Seq("Possibly too many singletons injected: d injected 2 times instead of 1, e injected 2 times instead of 1")

    tp.shutdownNow()
  }

}
