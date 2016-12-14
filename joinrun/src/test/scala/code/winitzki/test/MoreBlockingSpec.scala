package code.winitzki.test

import code.winitzki.jc.FixedPool
import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Macros._
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.concurrent.Waiters.Waiter
import org.scalatest.time.{Millis, Span}

import scala.concurrent.duration._

class MoreBlockingSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(1500, Millis)

  it should "wait for blocking molecule before injecting non-blocking molecule" in {
    val a = m[Int]
    val f = b[Unit,Int]
    val g = b[Unit,Int]
    val c = m[Int]
    val d = m[Int]

    val tp = new FixedPool(20)

    join(tp,tp)(
      &{ case f(_, r) => r(123) },
      &{ case g(_, r) + a(x) => r(x) },
      &{ case g(_, r) + d(x) => r(-x) },
      &{ case c(x) => val y = f(); if (y>0) d(x) else a(x) }
    )

    c(-2)
    g() shouldEqual 2
    tp.shutdownNow()
  }

  it should "return true if blocking molecule had a successful reply" in {
    val f = b[Unit,Int]

    val waiter = new Waiter

    val tp = new FixedPool(20)

    join(tp)(
      & { case f(_, r) => val res = r(123); waiter { res shouldEqual true }; waiter.dismiss() }
    )
    f(timeout = 50 millis)() shouldEqual Some(123)

    waiter.await()
    tp.shutdownNow()
  }

  it should "remove blocking molecule from soup after timeout" in {
    val a = m[Int]
    val f = b[Unit,Int]

    val tp = new FixedPool(20)

    join(tp)(
      & { case f(_, r) + a(x) => r(x); a(0) }
    )
    a.setLogLevel(4)
    a.logSoup shouldEqual "Join{a + f/B => ...}\nNo molecules"
    f(timeout = 50 millis)() shouldEqual None
    // there should be no a(0) now, because the reaction has not yet run ("f" timed out and was withdrawn, so no molecules)
    a.logSoup shouldEqual "Join{a + f/B => ...}\nNo molecules"
    a(123)
    // there still should be no a(0), because the reaction did not run (have "a" but no "f")
    f() shouldEqual 123
    // now there should be a(0) because the reaction has run
    Thread.sleep(150)
    a.logSoup shouldEqual "Join{a + f/B => ...}\nMolecules: a(0)"

    tp.shutdownNow()
  }

  it should "ignore reply to blocking molecule after timeout" in {
    val a = m[Int]
    val f = b[Unit,Int]
    val g = b[Unit,Int]

    val waiter = new Waiter

    val tp = new FixedPool(20)

    join(tp)(
      & { case f(_, r) => val x = g(); val res = r(x); waiter { res shouldEqual false }; waiter.dismiss() },
      & { case g(_, r) + a(x) => r(x) }
    )

    a.logSoup shouldEqual "Join{a + g/B => ...; f/B => ...}\nNo molecules"
    f(timeout = 50 millis)() shouldEqual None // this times out because the f => ... reaction is blocked by g(), which is waiting for a()
    a.logSoup shouldEqual "Join{a + g/B => ...; f/B => ...}\nMolecules: g/B()" // f() should have been removed but g() remains
    a(123) // Now g() starts reacting with a() and unblocks the "f" reaction, which should try to reply to "f" after "f" timed out.
    // The attempt to reply to "f" should fail, which is indicated by returning "false" from "r(x)". This is verified by the "waiter".
    Thread.sleep(50)
    waiter.await()
    tp.shutdownNow()
    a.logSoup shouldEqual "Join{a + g/B => ...; f/B => ...}\nNo molecules"
  }

  it should "correctly handle multiple blocking molecules of the same sort" in {
    val a = m[Int]
    val c = m[Int]
    val f = b[Int,Int]

    val tp = new FixedPool(20)
    join(tp)(
      & { case f(x, r) + a(y) => c(x); val s = f(x+y); r(s) },
      & { case f(x, r) + c(y) => r(x*y) }
    )

    a(10)
    f(20) shouldEqual 600 // c(20) + f(30, r) => r(600)

    tp.shutdownNow()
  }

}
