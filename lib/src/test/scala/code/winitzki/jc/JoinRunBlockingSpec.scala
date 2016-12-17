package code.winitzki.jc

import JoinRun._
import Library.withPool
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.{Millis, Span}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpec, Matchers}

import scala.concurrent.duration.DurationInt

/** More unit tests for blocking molecule functionality.
  *
  */
class JoinRunBlockingSpec extends FlatSpec with Matchers with TimeLimitedTests with BeforeAndAfterEach {

  var tp0: Pool = null

  override def beforeEach(): Unit = {
    tp0 = new SmartPool(50)
  }

  override def afterEach(): Unit = {
    tp0.shutdownNow()
  }

  val timeLimit = Span(1500, Millis)

  val warmupTimeMs = 50

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

  behavior of "blocking molecule"

  it should "block for a blocking molecule" in {

    val a = new M[Unit]("a")
    val f = new B[Unit,Int]("f")
    join(tp0)( runSimple { case a(_) + f(_, r) => r(3) })
    a()
    a()
    a()
    f() shouldEqual 3
    f() shouldEqual 3
    f() shouldEqual 3
  }

  it should "not timeout when a blocking molecule is responding" in {

    (1 to 1000).map { _ =>
      val a = new M[Unit]("a")
      val f = new B[Unit,Int]("f")
      join(tp0)( runSimple { case a(_) + f(_, r) => r(0) })
      a()
      f(timeout = 100 millis)().getOrElse(1)
    }.sum shouldEqual 0 // have about 4% failure rate here!
  }

  it should "timeout when a blocking molecule is not responding at all" in {

    val a = new M[Unit]("a")
    val f = new B[Unit,Int]("f")
    join(tp0)( runSimple { case a(_) + f(_, r) => r(3) })
    a()
    f() shouldEqual 3 // now the a() molecule is gone
    f(timeout = 100 millis)() shouldEqual None
  }

  it should "not timeout when a blocking molecule is responding quickly enough" in {

    val a = new M[Unit]("a")
    val f = new B[Unit,Int]("f")
    join(tp0)( runSimple { case a(_) + f(_, r) => Thread.sleep(50); r(3) })
    a()
    f(timeout = 100 millis)() shouldEqual Some(3)
  }

  it should "timeout when a blocking molecule is not responding quickly enough" in {

    val a = new M[Unit]("a")
    val f = new B[Unit,Int]("f")
    join(tp0)( runSimple { case a(_) + f(_, r) => Thread.sleep(150); r(3) })
    a()
    f(timeout = 100 millis)() shouldEqual None
  }

  behavior of "join definitions with invalid replies"

  it should "use the first reply when a reaction attempts to reply twice" in {
    val c = new M[Int]("c")
    val g = new B[Unit,Int]("g")
    join(tp0)(
      runSimple { case c(n) + g(_,r) => c(n); r(n); r(n+1) }
    )
    c(2)
    waitSome()

    g() shouldEqual 2
  }

  it should "use the first replies when a reaction attempts to reply twice to more than one molecule" in {
    val c = new M[Int]("c")
    val d = new M[Unit]("d")
    val d2 = new M[Int]("d2")
    val e = new B[Unit,Int]("e")
    val g = new B[Unit,Int]("g")
    val g2 = new B[Unit,Int]("g2")
    join(tp0)(
      runSimple { case d(_)  => d2(g2()) },
      runSimple { case d2(x) + e(_, r) => r(x) },
      runSimple { case c(n) + g(_,r) + g2(_, r2) => c(n); r(n); r2(n); Thread.sleep(100); r(n+1); r2(n+1) }
    )
    c(2) + d()
    g() shouldEqual 2
    e() shouldEqual 2

  }

  it should "throw exception when a reaction does not reply to one blocking molecule" in {
    val c = new M[Unit]("c")
    val g = new B[Unit,Int]("g")
    join(tp0)(
      runSimple { case c(_) + g(_,r) => c() }
    )
    c()
    waitSome()

    val thrown = intercept[Exception] {
      println(s"got result: ${g()} but should not have printed this!")
    }
    thrown.getMessage shouldEqual "Error: In Join{c + g/B => ...}: Reaction {c + g/B => ...} finished without replying to g/B"
  }

  it should "throw exception when a reaction does not reply to two blocking molecules)" in {
    val c = new M[Unit]("c")
    val d = new M[Unit]("d")
    val g = new B[Unit,Int]("g")
    val g2 = new B[Unit,Int]("g2")
    val tp = new FixedPool(4)
    join(tp,tp)(
      runSimple { case d(_) => g2() } onThreads tp,
      runSimple { case c(_) + g(_,_) + g2(_,_) => c() }
    )
    c() + d()
    waitSome()

    val thrown = intercept[Exception] {
      println(s"got result2: ${g()} but should not have printed this!")
    }
    thrown.getMessage shouldEqual "Error: In Join{c + g/B + g2/B => ...; d => ...}: Reaction {c + g/B + g2/B => ...} finished without replying to g/B, g2/B"

    tp.shutdownNow()
  }

  it should "get a reply when a reaction does not reply to one blocking molecule but does reply to another" in {
    val c = new M[Unit]("c")
    val d = new M[Unit]("d")
    val g = new B[Unit,Int]("g")
    val g2 = new B[Unit,Int]("g2")
    val tp = new FixedPool(4)
    join(tp,tp)(
      runSimple { case d(_) => g() } onThreads tp,
      runSimple { case c(_) + g(_,r) + g2(_,_) => c() + r(0) }
    )
    c() + d()
    waitSome()

    val thrown = intercept[Exception] {
      println(s"got result2: ${g2()} but should not have printed this!")
    }
    thrown.getMessage shouldEqual "Error: In Join{c + g/B + g2/B => ...; d => ...}: Reaction {c + g/B + g2/B => ...} finished without replying to g2/B"

    tp.shutdownNow()
  }

  behavior of "deadlocked threads with blocking molecules"

  it should "not produce deadlock when two blocking molecules are injected from different threads" in {
    val c = new M[Unit]("c")
    val d = new M[Unit]("d")
    val e = new M[Int]("e")
    val f = new M[Unit]("f")
    val g = new B[Unit,Int]("g")
    val g2 = new B[Unit,Int]("g2")
    val h = new B[Unit,Int]("h")
    val tp = new FixedPool(4)
    join(tp,tp)(
      runSimple { case c(_) => e(g2()) }, // e(0) should be injected now
      runSimple { case d(_) + g(_,r) + g2(_,r2) => r(0); r2(0) } onThreads tp,
      runSimple { case e(x) + h(_,r) =>  r(x) }
    )
    c()+d()
    waitSome()
    g() shouldEqual 0
    // now we should also have e(0)
    h() shouldEqual 0

    tp.shutdownNow()

  }

  it should "produce deadlock when two blocking molecules are injected from the same thread" in {
    val c = new M[Unit]("c")
    val d = new M[Unit]("d")
    val e = new M[Int]("e")
    val f = new M[Unit]("f")
    val g = new B[Unit,Int]("g")
    val g2 = new B[Unit,Int]("g2")
    val h = new B[Unit,Int]("h")
    val tp = new FixedPool(4)
    join(tp,tp)(
      runSimple { case c(_) => val x = g(); g2(); e(x) }, // e(0) should never be injected because this thread is deadlocked
      runSimple { case d(_) + g(_,r) + g2(_,r2) => r(0); r2(0) } onThreads tp,
      runSimple { case e(x) + h(_,r) =>  r(x) },
      runSimple { case d(_) + f(_) => e(2) },
      runSimple { case f(_) + e(_) => e(1) }
    )
    d()
    waitSome()
    c()
    waitSome()
    // if e(0) exists now, it will react with f() and produce e(1)
    f()
    waitSome()
    // if e(0) did not appear, f() is still available and will now react with d and produce e(2)
    d()
    waitSome()
    h() shouldEqual 2

    tp.shutdownNow()
  }

  it should "not block the 2-thread threadpool when one thread is blocked" in {
    val c = new M[Unit]("c")
    val d = new M[Unit]("d")
    val g = new B[Unit,Int]("g")
    val g2 = new B[Unit,Int]("g2")
    val tp = new FixedPool(4)
    join(tp,tp)(
      runSimple { case d(_) => g() }, // this will be used to inject g() and blocked
      runSimple { case c(_) + g(_,r) => r(0) }, // this will not start because we have no c()
      runSimple { case g2(_, r) => r(1) } // we will use this to test whether the entire thread pool is blocked
    )
    g2() shouldEqual 1 // this should initially work
    d() // do not inject c(). Now the first reaction is blocked because second reaction cannot start.
    g2() shouldEqual 1 // this should continue to work
    tp.shutdownNow()
  }

  it should "block the 1-thread threadpool when one thread is blocked" in {
    val c = new M[Unit]("c")
    val d = new M[Unit]("d")
    val g = new B[Unit,Int]("g")
    val g2 = new B[Unit,Int]("g2")
    val tp = new FixedPool(1)
    val tp1 = new FixedPool(1)
    join(tp,tp1)(
      runSimple { case d(_) => g() }, // this will be used to inject g() and block one thread
      runSimple { case c(_) + g(_,r) => r(0) }, // this will not start because we have no c()
      runSimple { case g2(_, r) => r(1) } // we will use this to test whether the entire thread pool is blocked
    )
    g2() shouldEqual 1 // this should initially work
    d() // do not inject c(). Now the first reaction is blocked because second reaction cannot start.
    waitSome()
    g2(timeout = 100 millis)() shouldEqual None // this should be blocked now
    tp.shutdownNow()
    tp1.shutdownNow()

  }

  def makeBlockingCheck(sleeping: => Unit, tp1: Pool): (B[Unit,Unit], B[Unit,Int]) = {
    val c = new M[Unit]("c")
    val d = new M[Unit]("d")
    val g = new B[Unit,Unit]("g")
    val g2 = new B[Unit,Int]("g2")

    join(tp0)( runSimple { case c(_) + g(_, r) => r() } ) // we will use this to monitor the d() reaction

    join(tp1,tp0)(
      runSimple { case d(_) => c(); sleeping; c() }, // this thread is blocked by sleeping
      runSimple { case g2(_, r) => r(1) } // we will use this to test whether the entire thread pool is blocked
    )
    g2() shouldEqual 1 // this should initially work, since d() has not yet been injected.
    d() // Now the first reaction will be starting soon.
    g() // Now we know that the first reaction has started and is sleeping.
    (g, g2)
  }

  it should "block the fixed threadpool when one thread is sleeping with Thread.sleep" in {
    val tp = new FixedPool(1)
    val (g, g2) = makeBlockingCheck(Thread.sleep(300), tp)
    g2(timeout = 150 millis)() shouldEqual None // this should be blocked
    tp.shutdownNow()
  }

  it should "block the fixed threadpool when one thread is sleeping with BlockingIdle(Thread.sleep)" in {
    val tp = new FixedPool(1)
    val (g, g2) = makeBlockingCheck(BlockingIdle{Thread.sleep(300)}, tp)
    g2(timeout = 150 millis)() shouldEqual None // this should be blocked
    tp.shutdownNow()
  }

  it should "block the cached threadpool when one thread is sleeping with Thread.sleep" in {
    withPool(new CachedPool(1)){ tp =>
      val (g, g2) = makeBlockingCheck(Thread.sleep(300), tp)
      g2(timeout = 150 millis)() shouldEqual None // this should be blocked
    }
  }

  it should "block the cached threadpool with BlockingIdle(Thread.sleep)" in {
    withPool(new CachedPool(1)) { tp =>
      val (g, g2) = makeBlockingCheck(BlockingIdle {Thread.sleep(300)}, tp)
      g2(timeout = 150 millis)() shouldEqual None // this should be blocked
    }
  }

  it should "not block the smart threadpool with BlockingIdle(Thread.sleep)" in {
    val tp = new SmartPool(1)
    val (g, g2) = makeBlockingCheck(BlockingIdle{Thread.sleep(500)}, tp)
    g2(timeout = 50 millis)() shouldEqual Some(1) // this should not be blocked
    tp.currentPoolSize shouldEqual 2
    g() // now we know that the first reaction has finished
    tp.currentPoolSize shouldEqual 1
    tp.shutdownNow()
  }

  it should "implement BlockingIdle(BlockingIdle()) as BlockingIdle()" in {
    val tp = new SmartPool(1)
    val (g, g2) = makeBlockingCheck(BlockingIdle{BlockingIdle{Thread.sleep(300)}}, tp)
    g2(timeout = 50 millis)() shouldEqual Some(1) // this should not be blocked
    tp.currentPoolSize shouldEqual 2
    g() // now we know that the first reaction has finished
    tp.currentPoolSize shouldEqual 1
    tp.shutdownNow()
  }

  behavior of "thread starvation with different threadpools"

  def blockThreadsDueToBlockingMolecule(tp1: Pool): B[Unit, Unit] = {
    val c = new M[Unit]("c")
    val cStarted = new M[Unit]("cStarted")
    val c2 = new M[Unit]("c2")
    val never = new M[Unit]("never")
    val f = new B[Unit,Int]("g")
    val f2 = new B[Unit,Int]("f2")
    val g = new B[Unit,Unit]("g")
    val started = new B[Unit,Unit]("started")

    join(tp1,tp0)(
      runSimple { case g(_, r) => r() }, // and so this reaction will be blocked forever
      runSimple { case c(_) => cStarted(); println(f()) }, // this reaction is blocked forever because f() does not reply
      runSimple { case c2(_) + cStarted(_) + started(_, r) => r(); println(f2()) }, // this reaction is blocked forever because f2() does not reply
      runSimple { case f(_, r) + never(_) => r(0)}, // this will never reply since "never" is never injected
      runSimple { case f2(_, r) + never(_) => r(0)} // this will never reply since "never" is never injected
    )

    c() + c2()
    started() // now we are sure that both reactions are running and stuck

    g
  }

  it should "block the fixed threadpool when all threads are waiting for new reactions" in {
    withPool(new FixedPool(2)) { tp =>
      val g = blockThreadsDueToBlockingMolecule(tp)
      g(timeout = 200 millis)() shouldEqual None
    }
  }

  it should "not block the fixed threadpool when more threads are available" in {
    withPool(new FixedPool(3)) { tp =>
      val g = blockThreadsDueToBlockingMolecule(tp)
      g(timeout = 200 millis)() shouldEqual Some(())
    }
  }

  it should "block the cached threadpool when all threads are waiting for new reactions" in {
    val tp = new CachedPool(2)
    val g = blockThreadsDueToBlockingMolecule(tp)
    g(timeout = 100 millis)() shouldEqual None
    tp.shutdownNow()
  }

  it should "not block the cached threadpool when more threads are available" in {
    val tp = new CachedPool(3)
    val g = blockThreadsDueToBlockingMolecule(tp)
    g(timeout = 200 millis)() shouldEqual Some(())
    tp.shutdownNow()
  }

  it should "not block the smart threadpool when all threads are waiting for new reactions" in {
    val tp = new SmartPool(2)
    val g = blockThreadsDueToBlockingMolecule(tp)
    g(timeout = 200 millis)() shouldEqual Some(())
    tp.shutdownNow()
  }

  it should "not block the smart threadpool when more threads are available" in {
    val tp = new SmartPool(3)
    val g = blockThreadsDueToBlockingMolecule(tp)
    g(timeout = 200 millis)() shouldEqual Some(())
    tp.shutdownNow()
  }

}
