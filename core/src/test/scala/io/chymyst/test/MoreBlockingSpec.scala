package io.chymyst.test

import io.chymyst.jc._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.language.postfixOps

class MoreBlockingSpec extends LogSpec {

  behavior of "blocking molecules"

  it should "wait for blocking molecule before emitting non-blocking molecule" in {
    val a = m[Int]
    val f = b[Unit, Int]
    val g = b[Unit, Int]
    val c = m[Int]
    val d = m[Int]

    val tp = FixedPool(6)

    site(tp)(
      go { case f(_, r) => r(123) },
      go { case g(_, r) + a(x) => r(x) },
      go { case g(_, r) + d(x) => r(-x) },
      go { case c(x) => val y = f(); if (y > 0) d(x) else a(x) }
    )

    c(-2)
    g() shouldEqual 2
    tp.shutdownNow()
  }

  it should "return false if blocking molecule timed out" in {
    val a = m[Boolean]
    val f = b[Unit, Int]
    val g = b[Unit, Boolean]

    val tp = FixedPool(4)

    site(tp)(
      go { case g(_, r) + a(x) => r(x) },
      go { case f(_, r) + a(true) => Thread.sleep(500); a(r(123)) }
    )
    a(true)
    f.timeout()(300.millis) shouldEqual None // should give enough time so that the reaction can start
    g() shouldEqual false // will be `true` when the `f` reaction did not start before timeout

    tp.shutdownNow()
  }

  it should "return false if blocking molecule timed out, with an empty reply value" in {
    val a = m[Boolean]
    val f = b[Unit, Unit]
    val g = b[Unit, Boolean]

    val tp = FixedPool(4)

    site(tp)(
      go { case g(_, r) + a(x) => r(x) },
      go { case f(_, r) + a(true) => Thread.sleep(500); a(r()) }
    )
    a(true)
    f.timeout()(300.millis) shouldEqual None // should give enough time so that the reaction can start
    g() shouldEqual false

    tp.shutdownNow()
  }

  it should "return true if blocking molecule had a successful reply" in {
    val f = b[Unit, Int]

    val waiter = Promise[Boolean]() // this is only used as a "waiter" for this async test; we are not testing Promise functionality here.

    val tp = FixedPool(4)

    site(tp)(
      go { case f(_, r) => val res = r(123); waiter.success(res) }
    )
    f.timeout()(10.seconds) shouldEqual Some(123)

    Await.result(waiter.future, Duration.Inf) shouldEqual true
    tp.shutdownNow()
  }

  it should "check status after blocking molecule had a successful reply" in {
    val f = b[Unit, Int]

    val waiter = Promise[Any]() // this is only used as a "waiter" for this async test; we are not testing Promise functionality here.

    val tp = FixedPool(4)

    site(tp)(
      go { case f(_, r) =>
        r(123)
        val res = r.noReplyAttemptedYet
        waiter.success((r.noReplyAttemptedYet, res, f.name, f.typeSymbol, f.isBound, f.isPipelined))
      }
    )
    f.name shouldEqual "f"
    f.typeSymbol shouldEqual 'Unit
    f.isBound shouldEqual true
    f.isPipelined shouldEqual true
    (f.name === "f" && f.typeSymbol === 'Unit && f.isBound && f.isPipelined) shouldEqual true
    f.timeout()(10.seconds) shouldEqual Some(123)

    Await.result(waiter.future, Duration.Inf) shouldEqual ((false, false, "f", 'Unit, true, true))
    tp.shutdownNow()
  }

  // warning: this test sometimes fails
  it should "return true for many blocking molecules with successful reply" in {
    val a = m[Boolean]
    val collect = m[Int]
    val f = b[Unit, Int]
    val get = b[Unit, Int]

    val tp = FixedPool(6)

    site(tp)(
      go { case f(_, reply) => a(reply(123)) },
      go { case a(x) + collect(n) => collect(n + (if (x) 0 else 1)) },
      go { case collect(n) + get(_, reply) => reply(n) }
    )
    collect(0)

    val numberOfFailures = (1 to 1000).map { _ =>
      if (f.timeout()(1000 millis).isEmpty) 1 else 0
    }.sum

    // we used to have about 4% numberOfFailures (but we get zero failures if we do not nullify the semaphore!) and about 4 numberOfFalseReplies in 100,000.
    // now it seems to pass even with a million iterations (but that's too long for Travis).
    val numberOfFalseReplies = get.timeout()(2000 millis)

    (numberOfFailures, numberOfFalseReplies) shouldEqual ((0, Some(0)))

    tp.shutdownNow()
  }

  it should "remove blocking molecule from soup after timeout" in {
    val a = m[Int]
    val f = b[Unit, Int]

    val tp = BlockingPool(2).withReporter(ConsoleDebugAllReporter)

    site(tp)(
      go { case f(_, r) + a(x) => r(x); a(0) }
    )
    a.logSite shouldEqual "Site{a + f/B → ...}\nNo molecules"
    f.timeout()(100 millis) shouldEqual None
    Thread.sleep(300)
    // TODO: this test sometimes fails because f is not withdrawn and reacts with a(123) - even though logSite prints "No molecules"!
    // there should be no a(0) now, because the reaction has not yet run ("f" timed out and was withdrawn, so no molecules)
    a.logSite shouldEqual "Site{a + f/B → ...}\nNo molecules"
    a(123)
    // there still should be no a(0), because the reaction did not run (have "a" but no "f")
    f() shouldEqual 123
    // now there should be a(0) because the reaction has run
    Thread.sleep(150)
    a.logSite shouldEqual "Site{a + f/B → ...}\nMolecules: a/P(0)"

    tp.shutdownNow()
  }

  it should "correctly handle multiple blocking molecules of the same sort" in {
    val a = m[Int]
    val c = m[Int]
    val f = b[Int, Int]

    val tp = FixedPool(6)
    site(tp)(
      go { case f(x, r) + a(y) => c(x); val s = f(x + y); r(s) },
      go { case f(x, r) + c(y) => r(x * y) }
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

    val tp = FixedPool(6)

    site(tp)(
      go { case get_d(_, r) + d(x) => r(x) },
      go { case wait(_, r) + e(_) => r() },
      go { case d(x) + incr(_, r) => r(); wait(); d(x + 1) }
    )
    d(100)
    incr() // reaction 3 started and is waiting for e()
    get_d.timeout()(400 millis) shouldEqual None
    e()
    get_d.timeout()(800 millis) shouldEqual Some(101)

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

    val tp = FixedPool(6)

    site(tp)(
      go { case get_f(_, r) + f(x) => r(x) },
      go { case c(_) => incr(); e() },
      go { case wait(_, r) + e(_) => r() },
      go { case d(x) + incr(_, r) => r(); wait(); f(x + 1) }
    )
    d(100)
    c() // update started and is waiting for e(), which should come after incr() gets its reply
    get_f() shouldEqual 101

    tp.shutdownNow()
  }

  val deadlockMessage = "Warning: deadlock occurred in FixedPool:tp (2 threads) due to 2 concurrent blocking calls while running reaction {d(x) + incr/B(_) → wait/B() + f(?)}"

  it should "deadlock without warning, since another reaction is blocked until molecule is emitted" in {
    val c = m[Unit]
    val d = m[Int]
    val e = m[Unit]
    val f = m[Int]
    val wait = b[Unit, Unit]
    val incr = b[Unit, Unit]
    val get_f = b[Unit, Int]

    val tp = FixedPool(4)
    val memLog = new MemoryLogger
    tp.reporter = new DebugAllReporter(memLog)

    site(tp)(
      go { case get_f(_, r) + f(x) => r(x) },
      go { case c(_) => incr(); e() },
      go { case wait(_, r) + e(_) => r() },
      go { case d(x) + incr(_, r) => wait(); r(); f(x) }
    )

    d(100)
    c() // update started and is waiting for e(), which should come after incr() gets its reply
    get_f.timeout()(1000 millis) shouldEqual None // deadlock: get_f() waits for f(), which will be emitted only after wait() returns; the reply to wait() is blocked by missing e(), which is emitted only after incr() returns, which also happens only after wait().
    // This deadlock cannot be detected by static analysis, unless we know that no other e() will be emitted.
    // All we know is that one thread is blocked by wait(), another by incr(), and that the reply to wait() requires a reaction wait + e -> ..., which is currently not running.
    memLog.messages.exists(_ endsWith deadlockMessage) shouldEqual false
    memLog.messages.foreach(println)
    tp.shutdownNow()
  }

  it should "print deadlock warning since another reaction is blocked until molecule is emitted" in {
    val c = m[Unit]
    val d = m[Int]
    val e = m[Unit]
    val f = m[Int]
    val wait = b[Unit, Unit]
    val incr = b[Unit, Unit]
    val get_f = b[Unit, Int]

    val memLog = new MemoryLogger
    val tp = FixedPool(2).withReporter(new ErrorsAndWarningsReporter(memLog))

    site(tp)(
      go { case get_f(_, r) + f(x) => r(x) },
      go { case c(_) => incr(); e() },
      go { case wait(_, r) + e(_) => r() },
      go { case d(x) + incr(_, r) => wait(); r(); f(x) }
    )
    d(100)
    c() // update started and is waiting for e(), which should come after incr() gets its reply
    get_f.timeout()(1000 millis) shouldEqual None // deadlock
    memLog.messages.exists(_ endsWith deadlockMessage) shouldEqual true

    memLog.clearLog()
    memLog.messages.size shouldEqual 0

    tp.shutdownNow()
  }

  behavior of "future emitters"

  it should "run reaction and get the future reply" in {
    val f = b[Int, Int]

    val tp = FixedPool(4)

    site(tp)(
      go { case f(x, r) ⇒ Thread.sleep(100); r(x + 1) }
    )
    val futureR = f.futureReply(123)
    futureR.isCompleted shouldEqual false
    Await.result(futureR, Duration.Inf) shouldEqual 124
    tp.shutdownNow()
  }

  it should "run reaction and manipulate the future reply" in {
    val f = b[Unit, Int]

    val tp = FixedPool(4)

    implicit val _ = tp.executionContext

    site(tp)(
      go { case f(_, r) ⇒ Thread.sleep(100); r(123) }
    )
    val futureR = f.futureReply()
    futureR.isCompleted shouldEqual false
    val futureR2 = futureR.flatMap(i ⇒ Future(i + 1))
    futureR2.isCompleted shouldEqual false
    Await.result(futureR2, Duration.Inf) shouldEqual 124
    tp.shutdownNow()
  }
}
