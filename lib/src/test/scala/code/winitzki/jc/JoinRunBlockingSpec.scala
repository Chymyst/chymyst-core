package code.winitzki.jc

import JoinRun._
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.{Millis, Span}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

import scala.concurrent.duration.DurationInt

/** More unit tests for blocking molecule functionality.
  *
  */
class JoinRunBlockingSpec extends FlatSpec with Matchers with TimeLimitedTests with BeforeAndAfterAll {

  val tp0 = new FixedPool(4)

  override def afterAll() = {
    tp0.shutdownNow()
  }

  val timeLimit = Span(1000, Millis)

  val warmupTimeMs = 50

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

  behavior of "blocking molecule"

  it should "block for a blocking molecule" in {

    val a = new M[Unit]("a")
    val f = new B[Unit,Int]("f")
    join(tp0,tp0)( runSimple { case a(_) + f(_, r) => r(3) })
    a()
    a()
    a()
    f() shouldEqual 3
    f() shouldEqual 3
    f() shouldEqual 3
  }

  it should "not timeout when a blocking molecule is responding" in {

    val a = new M[Unit]("a")
    val f = new B[Unit,Int]("f")
    join(tp0,tp0)( runSimple { case a(_) + f(_, r) => r(3) })
    a()
    f(timeout = 100 millis)() shouldEqual Some(3)
  }

  it should "timeout when a blocking molecule is not responding at all" in {

    val a = new M[Unit]("a")
    val f = new B[Unit,Int]("f")
    join(tp0,tp0)( runSimple { case a(_) + f(_, r) => r(3) })
    a()
    f() shouldEqual 3 // now the a() molecule is gone
    f(timeout = 100 millis)() shouldEqual None
  }

  it should "not timeout when a blocking molecule is responding quickly enough" in {

    val a = new M[Unit]("a")
    val f = new B[Unit,Int]("f")
    join(tp0,tp0)( runSimple { case a(_) + f(_, r) => Thread.sleep(50); r(3) })
    a()
    f(timeout = 100 millis)() shouldEqual Some(3)
  }

  it should "timeout when a blocking molecule is not responding quickly enough" in {

    val a = new M[Unit]("a")
    val f = new B[Unit,Int]("f")
    join(tp0,tp0)( runSimple { case a(_) + f(_, r) => Thread.sleep(150); r(3) })
    a()
    f(timeout = 100 millis)() shouldEqual None
  }

  behavior of "join definitions with invalid replies"

  it should "throw exception when a reaction attempts to reply twice" in {
    val c = new M[Int]("c")
    val g = new B[Unit,Int]("g")
    join(tp0,tp0)(
      runSimple { case c(n) + g(_,r) => c(n) + r(n) + r(n+1) }
    )
    c(2)
    waitSome()

    val thrown = intercept[Exception] {
      println(s"got result: ${g()} but should not have printed this!")
    }
    thrown.getMessage shouldEqual "Error: In Join{c + g/B => ...}: Reaction {c + g/B => ...} replied to g/B more than once"
  }

  it should "throw exception when a reaction attempts to reply twice to more than one molecule" in {
    val c = new M[Int]("c")
    val d = new M[Unit]("d")
    val g = new B[Unit,Int]("g")
    val g2 = new B[Unit,Int]("g2")
    join(tp0,tp0)(
      runSimple { case d(_) => g2() },
      runSimple { case c(n) + g(_,r) + g2(_, r2) => c(n) + r(n) + r(n+1) + r2(n) + r2(n+1) }
    )
    c(2) + d()
    waitSome()

    val thrown = intercept[Exception] {
      println(s"got result: ${g()} but should not have printed this!")
    }
    thrown.getMessage shouldEqual "Error: In Join{d => ...; c + g/B + g2/B => ...}: Reaction {c + g/B + g2/B => ...} replied to g/B, g2/B more than once"
  }

  it should "throw exception when a reaction does not reply to one blocking molecule" in {
    val c = new M[Unit]("c")
    val g = new B[Unit,Int]("g")
    join(tp0,tp0)(
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
    val tp = new FixedPool(2)
    join(tp,tp)(
      runSimple { case d(_) => g2() } onThreads tp,
      runSimple { case c(_) + g(_,_) + g2(_,_) => c() }
    )
    c() + d()
    waitSome()

    val thrown = intercept[Exception] {
      println(s"got result2: ${g()} but should not have printed this!")
    }
    thrown.getMessage shouldEqual "Error: In Join{d => ...; c + g/B + g2/B => ...}: Reaction {c + g/B + g2/B => ...} finished without replying to g/B, g2/B"

    tp.shutdownNow()
  }

  it should "throw exception when a reaction does not reply to one blocking molecule but does reply to another" in {
    val c = new M[Unit]("c")
    val d = new M[Unit]("d")
    val g = new B[Unit,Int]("g")
    val g2 = new B[Unit,Int]("g2")
    val tp = new FixedPool(2)
    join(tp,tp)(
      runSimple { case d(_) => g2() } onThreads tp,
      runSimple { case c(_) + g(_,r) + g2(_,_) => c() + r(0) }
    )
    c() + d()
    waitSome()

    val thrown = intercept[Exception] {
      println(s"got result: ${g()} but should not have printed this!")
    }
    thrown.getMessage shouldEqual "Error: In Join{d => ...; c + g/B + g2/B => ...}: Reaction {c + g/B + g2/B => ...} finished without replying to g2/B"

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
    val tp = new FixedPool(2)
    join(tp,tp)(
      runSimple { case c(_) => e(g2()) } onThreads tp, // e(0) should be injected now
      runSimple { case d(_) + g(_,r) + g2(_,r2) => r(0) + r2(0) } onThreads tp,
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
    val tp = new FixedPool(2)
    join(tp,tp)(
      runSimple { case c(_) => val x = g() + g2(); e(x) } onThreads tp, // e(0) should never be injected because this thread is deadlocked
      runSimple { case d(_) + g(_,r) + g2(_,r2) => r(0) + r2(0) } onThreads tp,
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
    val tp = new FixedPool(2)
    join(tp,tp)(
      runSimple { case d(_) => g() } onThreads tp, // this will be used to inject g() and blocked
      runSimple { case c(_) + g(_,r) => r(0) } onThreads tp, // this will not start because we have no c()
      runSimple { case g2(_, r) => r(1) } onThreads tp // we will use this to test whether the entire thread pool is blocked
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
    join(tp)(
      runSimple { case d(_) => g() }, // this will be used to inject g() and block one thread
      runSimple { case c(_) + g(_,r) => r(0) }, // this will not start because we have no c()
      runSimple { case g2(_, r) => r(1) } // we will use this to test whether the entire thread pool is blocked
    )
    g2() shouldEqual 1 // this should initially work
    d() // do not inject c(). Now the first reaction is blocked because second reaction cannot start.
    waitSome()
    g2(timeout = 100 millis)() shouldEqual None // this should be blocked now
    tp.shutdownNow()
  }

  def makeBlockingCheck(sleeping: => Unit, tp1: Pool): (B[Unit,Unit], B[Unit,Int]) = {
    val c = new M[Unit]("c")
    val d = new M[Unit]("d")
    val g = new B[Unit,Unit]("g")
    val g2 = new B[Unit,Int]("g2")

    join(tp0,tp0)( runSimple { case c(_) + g(_, r) => r() } ) // we will use this to monitor the d() reaction

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
    g2(timeout = 150 millis)() shouldEqual None // this should be blocked now
    tp.shutdownNow()
  }

  it should "block the fixed threadpool when one thread is sleeping with BlockingIdle(Thread.sleep)" in {
    val tp = new FixedPool(1)
    val (g, g2) = makeBlockingCheck(BlockingIdle{Thread.sleep(300)}, tp)
    g2(timeout = 150 millis)() shouldEqual None // this should be blocked now
    tp.shutdownNow()
  }

  it should "block the cached threadpool when one thread is sleeping with Thread.sleep" in {
    val tp = new CachedPool(1)
    val (g, g2) = makeBlockingCheck(Thread.sleep(300), tp)
    g2(timeout = 150 millis)() shouldEqual None // this should be blocked now
    tp.shutdownNow()
  }

  it should "block the cached threadpool with BlockingIdle(Thread.sleep)" in {
    val tp = new CachedPool(1)
    val (g, g2) = makeBlockingCheck(BlockingIdle{Thread.sleep(300)}, tp)
    g2(timeout = 150 millis)() shouldEqual None // this should be blocked
    tp.shutdownNow()
  }

  it should "not block the smart threadpool with BlockingIdle(Thread.sleep)" in {
    val tp = new SmartPool(1)
    val (g, g2) = makeBlockingCheck(BlockingIdle{Thread.sleep(300)}, tp)
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
    val d = new M[Unit]("d")
    val e = new M[Unit]("e")
    val f = new B[Unit,Int]("g")
    val f2 = new B[Unit,Int]("f2")
    val g = new B[Unit,Unit]("g")

    join(tp1,tp0)(
      runSimple { case c(_) => f() }, // this reaction will wait
      runSimple { case d(_) => e(); f2() }, // together with this reaction
      runSimple { case e(_) + f(_, r) => r(0) }, // for this reaction to reply, but there won't be any threads left
      runSimple { case g(_, r) => r() }, // and so this reaction will be blocked forever
      runSimple { case f2(_, r) + c(_) => r(0)} // while this will never happen since "c" will be gone when "f2" is injected
    )

    c()
    waitSome()
    d()
    waitSome()

    g
  }

  it should "block the fixed threadpool when all threads are waiting for new reactions" in {
    val tp = new FixedPool(2)
    val g = blockThreadsDueToBlockingMolecule(tp)
    g(timeout = 100 millis)() shouldEqual None
    tp.shutdownNow()
  }

  it should "not block the fixed threadpool when more threads are available" in {
    val tp = new FixedPool(3)
    val g = blockThreadsDueToBlockingMolecule(tp)
    g(timeout = 100 millis)() shouldEqual Some(())
    tp.shutdownNow()
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
    g(timeout = 100 millis)() shouldEqual Some(())
    tp.shutdownNow()
  }

  it should "not block the smart threadpool when all threads are waiting for new reactions" in {
    val tp = new SmartPool(2)
    val g = blockThreadsDueToBlockingMolecule(tp)
    g(timeout = 100 millis)() shouldEqual Some(())
    tp.shutdownNow()
  }

  it should "not block the smart threadpool when more threads are available" in {
    val tp = new SmartPool(3)
    val g = blockThreadsDueToBlockingMolecule(tp)
    g(timeout = 100 millis)() shouldEqual Some(())
    tp.shutdownNow()
  }

}
