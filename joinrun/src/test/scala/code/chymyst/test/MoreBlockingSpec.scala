package code.chymyst.test

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import code.chymyst.jc._
import org.scalatest.{Args, FlatSpec, Matchers, Status}
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.concurrent.Waiters.Waiter
import org.scalatest.time.{Millis, Span}

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Random.nextInt

class MoreBlockingSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(3000, Millis)
  var testNumber = 0

  override def runTest(testName: String, args: Args): Status = {
    testNumber += 1
    println(s"Starting MoreBlockingSpec test $testNumber: $testName")
    super.runTest(testName, args)
    //(1 to 100).map {i => println(s"Iteration $i"); super.runTest(testName, args)}.reduce{ (s1, s2) => if (s1.succeeds) s2 else s1 }
  }

  behavior of "blocking molecules"

  it should "wait for blocking molecule before emitting non-blocking molecule" in {
    val a = m[Int]
    val f = b[Unit, Int]
    val g = b[Unit, Int]
    val c = m[Int]
    val d = m[Int]

    val tp = new FixedPool(6)

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

  val safeSize: Int => Float = x => if (x==0) 1.0f else x.toFloat

  it should "return false if blocking molecule timed out" in {
    val a = m[Boolean]
    val f = b[Unit,Int]
    val g = b[Unit, Boolean]

    val tp = new FixedPool(4)

    site(tp)(
      go { case g(_, r) + a(x) => r(x) },
      go { case f(_, r) + a(true) => Thread.sleep(500); a(r.checkTimeout(123)) }
    )
    a(true)
    f.timeout(300.millis)() shouldEqual None // should give enough time so that the reaction can start
    g() shouldEqual false  // will be `true` when the `f` reaction did not start before timeout

    tp.shutdownNow()
  }

  it should "measure simple statistics on reaction delay" in {
    val f = b[Unit,Unit]
    val tp = new SmartPool(4)
    site(tp)(
      go { case f(_, r) => BlockingIdle{Thread.sleep(1)}; r() } // reply immediately
    )
    val trials = 200
    val timeInit = LocalDateTime.now
    val results = (1 to trials).map { _ =>
      val timeInit = LocalDateTime.now
      f()
      val timeElapsed = timeInit.until(LocalDateTime.now, ChronoUnit.MICROS)
      timeElapsed
    }
    val timeElapsed = timeInit.until(LocalDateTime.now, ChronoUnit.MILLIS)
    val meanReplyDelay = results.sum / safeSize(results.size) / 1000 - 1
    println(s"Sequential test: Mean reply delay is $meanReplyDelay ms out of $trials trials; the test took $timeElapsed ms")
  }

  it should "measure simple statistics on reaction delay in parallel" in {
    val f = b[Unit, Unit]
    val counter = m[(Int, List[Long])]
    val all_done = b[Unit, List[Long]]
    val done = m[Long]
    val begin = m[Unit]
    val tp = new SmartPool(4)

    val trials = 200

    site(tp)(
      go { case begin(_) =>
        val timeInit = LocalDateTime.now
        f()
        val timeElapsed = timeInit.until(LocalDateTime.now, ChronoUnit.MICROS)
        done(timeElapsed)
      },
      go { case all_done(_, r) + counter((0, results)) => r(results) },
      go { case counter( (n, results) ) + done(res) if n > 0 => counter( (n-1, res :: results) ) },
      go { case f(timeOut, r) => BlockingIdle{Thread.sleep(1)}; r() }
    )

    counter((trials, Nil))
    (1 to trials).foreach(_ => begin())

    val timeInit = LocalDateTime.now
    (1 to trials).foreach { _ => begin() }
    val timeElapsed = timeInit.until(LocalDateTime.now, ChronoUnit.MILLIS)
    val results = all_done()
    val meanReplyDelay = results.sum / safeSize(results.size) / 1000 - 1
    println(s"Parallel test: Mean reply delay is $meanReplyDelay ms out of $trials trials; the test took $timeElapsed ms")
  }

  type Result = (Int, Int, Long, Boolean)

  case class MeasurementResult(resultTrueSize: Int, resultFalseSize: Int, timeoutDelayArraySize: Int, noTimeoutMeanShiftArraySize: Int, timeoutDelay: Float, noTimeoutDelay: Float, timeoutMeanShift: Float, noTimeoutMeanShift: Float, printout: String)

  def measureTimeoutDelays(trials: Int, maxTimeout: Int, tp: Pool): List[(Int, Int, Long, Boolean)] = {
    val f = b[Long, Unit]
    val counter = m[(Int, List[Result])]
    val all_done = b[Unit, List[Result]]
    val done = m[Result]
    val begin = m[Unit]

    site(tp)(
      go { case begin(_) =>
        val t1 = nextInt(maxTimeout)
        val t2 = nextInt(maxTimeout)
        val timeInit = LocalDateTime.now
        val res = f.timeout(t1.millis)(t2.toLong).isEmpty
        val timeElapsed = timeInit.until(LocalDateTime.now, ChronoUnit.MILLIS)
        done((t1, t2, timeElapsed, res))
      },
      go { case all_done(_, r) + counter((0, results)) => r(results) },
      go { case counter( (n, results) ) + done(res) if n > 0 => counter( (n-1, res :: results) ) },
      go { case f(timeOut, r) => BlockingIdle{Thread.sleep(timeOut)}; r() }
    )

    counter((trials, Nil))
    (1 to trials).foreach(_ => begin())

    all_done()
  }

  def processResults(result: List[Result]): MeasurementResult = {
    val (resultTrue, resultFalse) = result.partition(_._4)

    val resultFalseSize = resultFalse.size
    val resultTrueSize = resultTrue.size

    val timeoutDelayArray = resultTrue.map{ case (t1, t2, timeElapsed, _) => timeElapsed - t2 }.filter(_ > 0)
    val timeoutDelay = timeoutDelayArray.sum / safeSize(timeoutDelayArray.size)
    val noTimeoutDelay = resultFalse.map{ case (t1, t2, timeElapsed, _) => timeElapsed - t2 }.sum / safeSize(resultFalse.size)
    val timeoutMeanShift = resultTrue.map{ case (t1, t2, timeElapsed, _) => timeElapsed - t1 }.sum / safeSize(resultTrue.size)
    val noTimeoutMeanShiftArray = resultFalse.map{ case (t1, t2, timeElapsed, _) => timeElapsed - t1 }.filter(_ > 0)
    val noTimeoutMeanShift = noTimeoutMeanShiftArray.sum / safeSize(noTimeoutMeanShiftArray.size)

    val timeoutDelayArraySize = timeoutDelayArray.size
    val noTimeoutMeanShiftArraySize = noTimeoutMeanShiftArray.size

    val printout = s"""Results:   # samples      | delay     | mean shift
                      |----------------------------------------------------
                      | timeout     ${resultTrueSize} (${timeoutDelayArraySize} items) | ${timeoutDelay} | ${timeoutMeanShift}
                      |----------------------------------------------------
                      | no timeout  ${resultFalseSize} (${noTimeoutMeanShiftArraySize} items) | ${noTimeoutDelay} | ${noTimeoutMeanShift}
       """.stripMargin

    MeasurementResult(resultTrueSize, resultFalseSize, timeoutDelayArraySize, noTimeoutMeanShiftArraySize, timeoutDelay, noTimeoutDelay, timeoutMeanShift, noTimeoutMeanShift, printout)
  }

  it should "measure the timeout delay in parallel threads" in {
    val trials = 900
    val maxTimeout = 500

    val tp = new SmartPool(4)

    val result = processResults(measureTimeoutDelays(trials, maxTimeout, tp))

    println(result.printout)

    tp.shutdownNow()
  }

  it should "measure the timeout delay in single thread" in {
    val trials = 20
    val maxTimeout = 200

    val tp = new FixedPool(4)

    val result = processResults(measureTimeoutDelays(trials, maxTimeout, tp))

    println(result.printout)

    tp.shutdownNow()
  }

  it should "return false if blocking molecule timed out, with an empty reply value" in {
    val a = m[Boolean]
    val f = b[Unit,Unit]
    val g = b[Unit, Boolean]

    val tp = new FixedPool(4)

    site(tp)(
      go { case g(_, r) + a(x) => r(x) },
      go { case f(_, r) + a(true) => Thread.sleep(500); a(r.checkTimeout()) }
    )
    a(true)
    f.timeout(300.millis)() shouldEqual None // should give enough time so that the reaction can start
    g() shouldEqual false

    tp.shutdownNow()
  }

  it should "return true if blocking molecule had a successful reply" in {
    val f = b[Unit,Int]

    val waiter = new Waiter

    val tp = new FixedPool(4)

    site(tp)(
      go { case f(_, r) => val res = r.checkTimeout(123); waiter { res shouldEqual true; ()}; waiter.dismiss() }
    )
    f.timeout(10.seconds)() shouldEqual Some(123)

    waiter.await()
    tp.shutdownNow()
  }

  // warning: this test sometimes fails
  it should "return true for many blocking molecules with successful reply" in {
    val a = m[Boolean]
    val collect = m[Int]
    val f = b[Unit,Int]
    val get = b[Unit, Int]

    val tp = new FixedPool(6)

    site(tp)(
      go { case f(_, reply) => a(reply.checkTimeout(123)) },
      go { case a(x) + collect(n) => collect(n + (if (x) 0 else 1))},
      go { case collect(n) + get(_, reply) => reply(n) }
    )
    collect(0)

    val numberOfFailures = (1 to 1000).map { _ =>
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

    val tp = new FixedPool(2)

    site(tp)(
      go { case f(_, r) + a(x) => r(x); a(0) }
    )
    a.setLogLevel(4)
    a.logSoup shouldEqual "Site{a + f/B => ...}\nNo molecules"
    f.timeout(100 millis)() shouldEqual None
    // TODO: this test sometimes fails because f is not withdrawn and reacts with a(123) - even though logSoup prints "No molecules"!
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
      go { case f(_, r) => val x = g(); val res = r.checkTimeout(x); waiter { res shouldEqual false; () }; waiter.dismiss() },
      go { case g(_, r) + a(x) => r(x) }
    )

    a.logSoup shouldEqual "Site{a + g/B => ...; f/B => ...}\nNo molecules"
    f.timeout(300 millis)() shouldEqual None // this times out because the f => ... reaction is blocked by g(), which is waiting for a()
    a.logSoup shouldEqual "Site{a + g/B => ...; f/B => ...}\nMolecules: g/B()" // f() should have been removed but g() remains
    a(123) // Now g() starts reacting with a() and unblocks the "f" reaction, which should try to reply to "f" after "f" timed out.
    // The attempt to reply to "f" should fail, which is indicated by returning "false" from "r(x)". This is verified by the "waiter".
    Thread.sleep(150)
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
      go { case d(x) + incr(_, r) => wait(); r() + f(x+1) }
    )
    d.setLogLevel(4)
    d(100)
    c() // update started and is waiting for e(), which should come after incr() gets its reply
    get_f.timeout(400 millis)() shouldEqual None

    tp.shutdownNow()
  }

}
