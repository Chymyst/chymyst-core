package code.chymyst.test

import code.chymyst.jc._
import org.scalatest.{FlatSpec, Matchers, BeforeAndAfterEach, Args, Status}
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.{Millis, Span}

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

/** More unit tests for blocking molecule functionality.
  *
  */
class BlockingMoleculesSpec extends FlatSpec with Matchers with TimeLimitedTests with BeforeAndAfterEach {

  var testNumber = 0

  override def runTest(testName: String, args: Args): Status = {
    testNumber += 1
    println(s"Starting blocking molecules test $testNumber: $testName")
    super.runTest(testName, args)
    //(1 to 100).map {i => println(s"Iteration $i"); super.runTest(testName, args)}.reduce{ (s1, s2) => if (s1.succeeds) s2 else s1 }
  }

  var tp0: Pool = _

  override def beforeEach(): Unit = {
    tp0 = new SmartPool(12)
  }

  override def afterEach(): Unit = {
    tp0.shutdownNow()
  }

  val timeLimit = Span(6000, Millis)

  behavior of "blocking molecule"

  it should "block for a blocking molecule" in {

    val a = m[Unit]
    val f = b[Unit, Int]
    site(tp0)(go { case a(_) + f(_, r) => r(3) })
    a()
    a()
    a()
    f() shouldEqual 3
    f() shouldEqual 3
    f() shouldEqual 3
  }

  it should "not timeout when a blocking molecule is responding" in {

    (1 to 1000).map { _ =>
      val f = b[Unit, Int]
      site(tp0)(go { case f(_, r) => r(0) })

      f.timeout()(500 millis).getOrElse(1)
    }.sum shouldEqual 0 // we used to have about 4% failure rate here!
  }

  it should "timeout when a blocking molecule is not responding at all" in {

    val a = m[Unit]
    val f = b[Unit, Int]
    site(tp0)(go { case a(_) + f(_, r) => r(3) })
    a()
    f() shouldEqual 3 // now the a() molecule is gone
    f.timeout()(500 millis) shouldEqual None
  }

  it should "not timeout when a blocking molecule is responding quickly enough" in {
    val a = m[Unit]
    val f = b[Unit, Int]
    site(tp0)(go { case a(_) + f(_, r) => Thread.sleep(100); r(3) })
    a()
    f.timeout()(500 millis) shouldEqual Some(3)
  }

  it should "timeout when a blocking molecule is not responding quickly enough" in {
    val a = m[Unit]
    val f = b[Unit, Int]
    site(tp0)(go { case a(_) + f(_, r) => Thread.sleep(500); r(3) })
    a()
    f.timeout()(200 millis) shouldEqual None
  }

  behavior of "syntax of blocking molecules with unit values / replies"

  it should "allow non-unit values and non-unit replies" in {
    val f = b[Int, Int]
    site(tp0)(go { case f(x, r) if x == 123 => r(456) })
    f(123) shouldEqual 456
  }

  it should "allow non-unit values and non-unit replies with timeout" in {
    val f = new B[Int, Int]("f")
    site(tp0)(go { case f(x, r) if x != 123 => r(456) })
    f.timeout(123)(200 millis) shouldEqual None
  }

  it should "allow non-unit values and unit replies" in {
    val f = b[Int, Unit]
    site(tp0)(go { case f(x, r) if x == 123 => r() })
    f(123) shouldEqual (())
  }

  it should "allow non-unit values and unit replies with timeout" in {
    val f = b[Int, Unit]
    site(tp0)(go { case f(x, r) if x != 123 => r() })
    f.timeout(123)(200 millis) shouldEqual None
  }

  it should "allow unit values and unit replies" in {
    val f = b[Unit, Unit]
    site(tp0)(go { case f(x, r) if x == (()) => r() })
    f() shouldEqual (())
  }

  it should "allow unit values and unit replies with timeout" in {
    val f = b[Unit, Unit]
    site(tp0)(go { case f(x, r) if x != (()) => r() })
    f.timeout()(200 millis) shouldEqual None
  }

  behavior of "deadlocked threads with blocking molecules"

  it should "not produce deadlock when two blocking molecules are emitted from different threads" in {
    val c = m[Unit]
    val d = m[Unit]
    val e = new M[Int]("e")
    val g = b[Unit, Int]
    val g2 = b[Unit, Int]
    val h = b[Unit, Int]
    val tp = new FixedPool(4)
    site(tp0)(
      go { case c(_) => e(g2()) }, // e(0) should be emitted now
      go { case d(_) + g(_, r) + g2(_, r2) => r(0) + r2(0) } onThreads tp,
      go { case e(x) + h(_, r) => r(x) }
    )
    c() + d()
    g() shouldEqual 0
    // now we should also have e(0)
    h() shouldEqual 0

    tp.shutdownNow()

  }

  it should "produce deadlock when two blocking molecules are emitted from the same thread" in {
    val c = m[Unit]
    val d = m[Unit]
    val e = new M[Int]("e")
    val f = m[Unit]
    val g = b[Unit, Int]
    val g2 = b[Unit, Int]
    val h = b[Unit, Int]
    val tp = new FixedPool(4)
    site(tp0)(
      go { case c(_) => val x = g(); g2(); e(x) }, // e(0) should never be emitted because this thread is deadlocked
      go { case g(_, r) + g2(_, r2) => r(0) + r2(0) } onThreads tp,
      go { case e(x) + h(_, r) => r(x) },
      go { case d(_) + f(_) => e(2) },
      go { case f(_) + e(_) => e(1) }
    )

    c()
    // if e(0) exists now, it will react with f() and produce e(1)
    f()
    h.timeout()(200 millis) shouldEqual None // make sure we still have neither e(0) nor e(1)
    // if e(0) did not appear, f() is still available and will now react with d and produce e(2)
    d()
    h() shouldEqual 2

    tp.shutdownNow()
  }

  it should "not block the 2-thread threadpool when one thread is blocked" in {
    val c = m[Unit]
    val d = m[Unit]
    val g = b[Unit, Int]
    val g2 = b[Unit, Int]
    val tp = new FixedPool(2)
    site(tp)(
      go { case d(_) => g() }, // this will be used to emit g() and blocked
      go { case c(_) + g(_, r) => r(0) }, // this will not start because we have no c()
      go { case g2(_, r) => r(1) } // we will use this to test whether the entire thread pool is blocked
    )
    g2() shouldEqual 1 // this should initially work
    d() // do not emit c(). Now the first reaction is blocked because second reaction cannot start.
    g2() shouldEqual 1 // this should continue to work
    tp.shutdownNow()
  }

  it should "block the 1-thread threadpool when one thread is blocked" in {
    val c = m[Unit]
    val d = m[Unit]
    val g = b[Unit, Int]
    val g2 = b[Unit, Int]
    val tp = new FixedPool(1)
    val tp1 = new FixedPool(1)
    site(tp, tp1)(
      go { case d(_) => g() }, // this will be used to emit g() and block one thread
      go { case c(_) + g(_, r) => r(0) }, // this will not start because we have no c()
      go { case g2(_, r) => r(1) } // we will use this to test whether the entire thread pool is blocked
    )
    g2() shouldEqual 1 // this should initially work
    d() // do not emit c(). Now the first reaction is blocked because second reaction cannot start.
    g2.timeout()(300 millis) shouldEqual None // this should be blocked now
    tp.shutdownNow()
    tp1.shutdownNow()
  }

  def makeBlockingCheck(sleeping: => Unit, tp1: Pool): (B[Unit, Unit], B[Unit, Int]) = {
    val c = m[Unit]
    val d = m[Unit]
    val g = b[Unit, Unit]
    val g2 = b[Unit, Int]

    site(tp0)(go { case c(_) + g(_, r) => r() }) // we will use this to monitor the d() reaction

    site(tp1, tp0)(
      go { case d(_) => c(); sleeping; c() }, // this thread is blocked by sleeping
      go { case g2(_, r) => r(1) } // we will use this to test whether the entire thread pool is blocked
    )
    g2() shouldEqual 1 // this should initially work, since d() has not yet been emitted.
    d() // Now the first reaction will be starting soon.
    g() // Now we know that the first reaction has started and is sleeping.
    (g, g2)
  }

  it should "block the fixed threadpool when one thread is sleeping with Thread.sleep" in {
    val tp = new FixedPool(1)
    val res = makeBlockingCheck(Thread.sleep(500), tp)
    res._2.timeout()(150 millis) shouldEqual None // this should be blocked
    tp.shutdownNow()
  }

  it should "block the fixed threadpool when one thread is sleeping with BlockingIdle(Thread.sleep)" in {
    val tp = new FixedPool(1)
    val res = makeBlockingCheck(BlockingIdle {
      Thread.sleep(500)
    }, tp)
    res._2.timeout()(150 millis) shouldEqual None // this should be blocked
    tp.shutdownNow()
  }

  it should "not block the smart threadpool with BlockingIdle(Thread.sleep)" in {
    val tp = new SmartPool(1)
    val (g, g2) = makeBlockingCheck(BlockingIdle {
      Thread.sleep(500)
    }, tp)
    g2.timeout()(150 millis) shouldEqual Some(1) // this should not be blocked
    tp.currentPoolSize shouldEqual 2
    g() // now we know that the first reaction has finished
    tp.currentPoolSize shouldEqual 1
    tp.shutdownNow()
  }

  it should "implement BlockingIdle(BlockingIdle()) as BlockingIdle()" in {
    val tp = new SmartPool(1)
    val (g, g2) = makeBlockingCheck(BlockingIdle {
      BlockingIdle {
        Thread.sleep(300)
      }
    }, tp)
    g2.timeout()(150 millis) shouldEqual Some(1) // this should not be blocked
    tp.currentPoolSize shouldEqual 2
    g() // now we know that the first reaction has finished
    tp.currentPoolSize shouldEqual 1
    tp.shutdownNow()
  }

  behavior of "thread starvation with different threadpools"

  def blockThreadsDueToBlockingMolecule(tp1: Pool): B[Unit, Unit] = {
    val c = m[Unit]
    val cStarted = m[Unit]
    val never = m[Unit]
    val f = b[Unit, Int]
    val f2 = b[Unit, Int]
    val g = b[Unit, Unit]
    val started = b[Unit, Unit]

    site(tp1, tp0)(
      go { case g(_, r) => r() }, // and so this reaction will be blocked forever
      go { case c(_) => cStarted(); val res = f(); println(res) }, // this reaction is blocked forever because f() does not reply
      go { case cStarted(_) + started(_, r) => r(); var res = f2(); println(res) }, // this reaction is blocked forever because f2() does not reply
      go { case f(_, r) + never(_) => r(0) }, // this will never reply since "never" is never emitted
      go { case f2(_, r) + never(_) => r(0) } // this will never reply since "never" is never emitted
    )

    c()
    started.timeout()(500 millis) shouldEqual Some(()) // now we are sure that both reactions are running and stuck
    g
  }

  it should "block the fixed threadpool when all threads are waiting for new reactions" in {
    withPool(new FixedPool(2)) { tp =>
      val g = blockThreadsDueToBlockingMolecule(tp)
      g.timeout()(200 millis) shouldEqual None
    }
  }

  it should "not block the fixed threadpool when more threads are available" in {
    withPool(new FixedPool(3)) { tp =>
      val g = blockThreadsDueToBlockingMolecule(tp)
      g.timeout()(200 millis) shouldEqual Some(())
    }
  }

  it should "not block the smart threadpool when all threads are waiting for new reactions" in {
    val tp = new SmartPool(2)
    val g = blockThreadsDueToBlockingMolecule(tp)
    g.timeout()(200 millis) shouldEqual Some(())
    tp.shutdownNow()
  }

  it should "not block the smart threadpool when more threads are available" in {
    val tp = new SmartPool(3)
    val g = blockThreadsDueToBlockingMolecule(tp)
    g.timeout()(200 millis) shouldEqual Some(())
    tp.shutdownNow()
  }

}
