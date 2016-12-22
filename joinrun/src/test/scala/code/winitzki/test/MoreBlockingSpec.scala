package code.winitzki.test

import code.winitzki.jc._
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.concurrent.Waiters.Waiter
import org.scalatest.time.{Millis, Span}

import scala.concurrent.duration._
import scala.language.postfixOps

class MoreBlockingSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(3000, Millis)

  behavior of "blocking molecules"

  it should "wait for blocking molecule before emitting non-blocking molecule" in {
    val a = m[Int]
    val f = b[Unit,Int]
    val g = b[Unit,Int]
    val c = m[Int]
    val d = m[Int]

    val tp = new FixedPool(6)

    site(tp)(
      go { case f(_, r) => r(123) },
      go { case g(_, r) + a(x) => r(x) },
      go { case g(_, r) + d(x) => r(-x) },
      go { case c(x) => val y = f(); if (y>0) d(x) else a(x) }
    )

    c(-2)
    g() shouldEqual 2
    tp.shutdownNow()
  }

  it should "return true if blocking molecule had a successful reply" in {
    val f = b[Unit,Int]

    val waiter = new Waiter

    val tp = new FixedPool(4)

    site(tp)(
      go { case f(_, r) => val res = r(123); waiter { res shouldEqual true; ()}; waiter.dismiss() }
    )
    f.timeout(10.seconds)() shouldEqual Some(123)

    waiter.await()
    tp.shutdownNow()
  }

  it should "return true for many blocking molecules with successful reply" in {
    val a = m[Boolean]
    val collect = m[Int]
    val f = b[Unit,Int]
    val get = b[Unit, Int]

    val tp = new FixedPool(6)

    site(tp)(
      go { case f(_, reply) => a(reply(123)) },
      go { case a(x) + collect(n) => collect(n + (if (x) 0 else 1))},
      go { case collect(n) + get(_, reply) => reply(n) }
    )
    collect(0)

    val numberOfFailures = (1 to 10000).map { _ =>
      if (f.timeout(1000 millis)().isEmpty) 1 else 0
    }.sum

    // we used to have about 4% numberOfFailures (but we get zero failures if we do not nullify the semaphore!) and about 4 numberOfFalseReplies in 100,000.
    // now it seems to pass even with a million iterations (but that's too long for Travis).
    val numberOfFalseReplies = get()

    (numberOfFailures, numberOfFalseReplies) shouldEqual ((0,0))

    tp.shutdownNow()
  }

  it should "remove blocking molecule from soup after timeout" in {
    val a = m[Int]
    val f = b[Unit,Int]

    val tp = new FixedPool(20)

    site(tp)(
      go { case f(_, r) + a(x) => r(x); a(0) }
    )
    a.setLogLevel(4)
    a.logSoup shouldEqual "Site{a + f/B => ...}\nNo molecules"
    f.timeout(100 millis)() shouldEqual None
    // there should be no a(0) now, because the reaction has not yet run ("f" timed out and was withdrawn, so no molecules)
    a.logSoup shouldEqual "Site{a + f/B => ...}\nNo molecules"
    a(123)
    // there still should be no a(0), because the reaction did not run (have "a" but no "f")
    f() shouldEqual 123
    // now there should be a(0) because the reaction has run
    Thread.sleep(150)
    a.logSoup shouldEqual "Site{a + f/B => ...}\nMolecules: a(0)"

    tp.shutdownNow()
  }

  it should "ignore reply to blocking molecule after timeout" in {
    val a = m[Int]
    val f = b[Unit,Int]
    val g = b[Unit,Int]

    val waiter = new Waiter

    val tp = new FixedPool(20)

    site(tp)(
      go { case f(_, r) => val x = g(); val res = r(x); waiter { res shouldEqual false; () }; waiter.dismiss() },
      go { case g(_, r) + a(x) => r(x) }
    )

    a.logSoup shouldEqual "Site{a + g/B => ...; f/B => ...}\nNo molecules"
    f.timeout(300 millis)() shouldEqual None // this times out because the f => ... reaction is blocked by g(), which is waiting for a()
    a.logSoup shouldEqual "Site{a + g/B => ...; f/B => ...}\nMolecules: g/B()" // f() should have been removed but g() remains
    a(123) // Now g() starts reacting with a() and unblocks the "f" reaction, which should try to reply to "f" after "f" timed out.
    // The attempt to reply to "f" should fail, which is indicated by returning "false" from "r(x)". This is verified by the "waiter".
    Thread.sleep(50)
    waiter.await()
    tp.shutdownNow()
    a.logSoup shouldEqual "Site{a + g/B => ...; f/B => ...}\nNo molecules"
  }

  it should "correctly handle multiple blocking molecules of the same sort" in {
    val a = m[Int]
    val c = m[Int]
    val f = b[Int,Int]

    val tp = new FixedPool(6)
    site(tp)(
      go { case f(x, r) + a(y) => c(x); val s = f(x+y); r(s) },
      go { case f(x, r) + c(y) => r(x*y) }
    )

    a(10)
    f(20) shouldEqual 600 // c(20) + f(30, r) => r(600)

    tp.shutdownNow()
  }

  it should "block execution thread until molecule is emitted" in {
    val d = m[Int]
    val e = m[Unit]
    val wait = b[Unit, Unit]
    val incr = b[Unit, Unit]
    val get_d = b[Unit, Int]

    val tp = new FixedPool(6)

    site(tp)(
      go { case get_d(_, r) + d(x) => r(x) },
      go { case wait(_, r) + e(_) => r() },
      go { case d(x) + incr(_, r) => r(); wait(); d(x+1) }
    )
    d(100)
    incr() // reaction 3 started and is waiting for e()
    get_d.timeout(400 millis)() shouldEqual None
    e()
    get_d.timeout(800 millis)() shouldEqual Some(101)

    tp.shutdownNow()
  }

  it should "block another reaction until molecule is emitted" in {
    val c = m[Unit]
    val d = m[Int]
    val e = m[Unit]
    val f = m[Int]
    val wait = b[Unit, Unit]
    val incr = b[Unit, Unit]
    val get_f = b[Unit, Int]

    val tp = new FixedPool(6)

    site(tp)(
      go { case get_f(_, r) + f(x) => r(x) },
      go { case c(_) => incr(); e() },
      go { case wait(_, r) + e(_) => r() },
      go { case d(x) + incr(_, r) => r(); wait(); f(x+1) }
    )
    d(100)
    c() // update started and is waiting for e(), which should come after incr() gets its reply
    get_f() shouldEqual 101

    tp.shutdownNow()
  }

  it should "deadlock since another reaction is blocked until molecule is emitted" in {
    val c = m[Unit]
    val d = m[Int]
    val e = m[Unit]
    val f = m[Int]
    val wait = b[Unit, Unit]
    val incr = b[Unit, Unit]
    val get_f = b[Unit, Int]

    val tp = new FixedPool(6)

    site(tp)(
      go { case get_f(_, r) + f(x) => r(x) },
      go { case c(_) => incr(); e() },
      go { case wait(_, r) + e(_) => r() },
      go { case d(x) + incr(_, r) => wait(); r(); f(x+1) }
    )
    d.setLogLevel(4)
    d(100)
    c() // update started and is waiting for e(), which should come after incr() gets its reply
    get_f.timeout(400 millis)() shouldEqual None

    tp.shutdownNow()
  }

}
