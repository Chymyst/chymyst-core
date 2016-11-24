package code.winitzki.jc

import JoinRun._
import org.scalatest.concurrent.TimeLimitedTests
import scala.concurrent.blocking
import org.scalatest.time.{Millis, Span}
import org.scalatest.{FlatSpec, Matchers}

/** More unit tests for blocking molecule functionality.
  *
  */
class JoinRunBlockingSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(500, Millis)

  val warmupTimeMs = 50

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

  behavior of "blocking molecule"
  
  it should "block for a blocking molecule" in {

    val a = new M[Unit]("a")
    val f = new B[Unit,Int]("f")
    join( &{ case a(_) + f(_, r) => r(3) })
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
    join( &{ case a(_) + f(_, r) => r(3) })
    a()
    f(timeout = 100000000)() shouldEqual Some(3)
  }

  it should "timeout when a blocking molecule is not responding at all" in {

    val a = new M[Unit]("a")
    val f = new B[Unit,Int]("f")
    join( &{ case a(_) + f(_, r) => r(3) })
    a()
    f() shouldEqual 3 // now the a() molecule is gone
    f(timeout = 100000000)() shouldEqual None
  }

  it should "not timeout when a blocking molecule is responding quickly enough" in {

    val a = new M[Unit]("a")
    val f = new B[Unit,Int]("f")
    join( &{ case a(_) + f(_, r) => Thread.sleep(50); r(3) })
    a()
    f(timeout = 100000000)() shouldEqual Some(3)
  }

  it should "timeout when a blocking molecule is not responding quickly enough" in {

    val a = new M[Unit]("a")
    val f = new B[Unit,Int]("f")
    join( &{ case a(_) + f(_, r) => Thread.sleep(150); r(3) })
    a()
    f(timeout = 100000000)() shouldEqual None
  }

  behavior of "join definitions with invalid replies"

  it should "throw exception when a reaction attempts to reply twice" in {
    val c = new M[Int]("c")
    val g = new B[Unit,Int]("g")
    join(
      &{ case c(n) + g(_,r) => c(n) + r(n) + r(n+1) }
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
    join(
      &{ case d(_) => g2() },
      &{ case c(n) + g(_,r) + g2(_, r2) => c(n) + r(n) + r(n+1) + r2(n) + r2(n+1) }
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
    join(
      &{ case c(_) + g(_,r) => c() }
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
    join(
      &{ case d(_) => g2() } onThreads tp,
      &{ case c(_) + g(_,_) + g2(_,_) => c() }
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
    join(
      &{ case d(_) => g2() } onThreads tp,
      &{ case c(_) + g(_,r) + g2(_,_) => c() + r(0) }
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
    join(
      &{ case c(_) => e(g2()) } onThreads tp, // e(0) should be injected now
      &{ case d(_) + g(_,r) + g2(_,r2) => r(0) + r2(0) } onThreads tp,
      &{ case e(x) + h(_,r) =>  r(x) }
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
    join(
      &{ case c(_) => val x = g() + g2(); e(x) } onThreads tp, // e(0) should never be injected because this thread is deadlocked
      &{ case d(_) + g(_,r) + g2(_,r2) => r(0) + r2(0) } onThreads tp,
      &{ case e(x) + h(_,r) =>  r(x) },
      &{ case d(_) + f(_) => e(2) },
      &{ case f(_) + e(_) => e(1) }
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
    join(
      &{ case d(_) => g() } onThreads tp, // this will be used to inject g() and blocked
      &{ case c(_) + g(_,r) => r(0) } onThreads tp, // this will not start because we have no c()
      &{ case g2(_, r) => r(1) } onThreads tp // we will use this to test whether the entire thread pool is blocked
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
    join(
      &{ case d(_) => g() } onThreads tp, // this will be used to inject g() and block one thread
      &{ case c(_) + g(_,r) => r(0) } onThreads tp, // this will not start because we have no c()
      &{ case g2(_, r) => r(1) } onThreads tp // we will use this to test whether the entire thread pool is blocked
    )
    g2() shouldEqual 1 // this should initially work
    d() // do not inject c(). Now the first reaction is blocked because second reaction cannot start.
    waitSome()
    g2(timeout = 1000000L*100)() shouldEqual None // this should be blocked now
    tp.shutdownNow()
  }

  it should "block the fixed threadpool when one thread is sleeping with Thread.sleep" in {
    val d = new M[Unit]("d")
    val g2 = new B[Unit,Int]("g2")
    val tp = new FixedPool(1)
    join(
      &{ case d(_) => Thread.sleep(300) } onThreads tp, // this thread is blocked by sleeping
      &{ case g2(_, r) => r(1) } onThreads tp // we will use this to test whether the entire thread pool is blocked
    )
    g2() shouldEqual 1 // this should initially work
    d() // Now the first reaction is sleeping.
    waitSome()
    g2(timeout = 1000000L*150)() shouldEqual None // this should be blocked now
    tp.shutdownNow()
  }

  it should "block the fixed threadpool when one thread is sleeping with blocking(Thread.sleep)" in {
    val d = new M[Unit]("d")
    val g2 = new B[Unit,Int]("g2")
    val tp = new FixedPool(1)
    join(
      &{ case d(_) => blocking {Thread.sleep(300)} } onThreads tp, // this thread is blocked by sleeping
      &{ case g2(_, r) => r(1) } onThreads tp // we will use this to test whether the entire thread pool is blocked
    )
    g2() shouldEqual 1 // this should initially work
    d() // Now the first reaction is sleeping.
    waitSome()
    g2(timeout = 1000000L*150)() shouldEqual None // this should be blocked now
    tp.shutdownNow()
  }

  it should "block the cached threadpool when one thread is sleeping with Thread.sleep" in {
    val d = new M[Unit]("d")
    val g2 = new B[Unit,Int]("g2")
    val tp = new CachedPool(1)
    join(
      &{ case d(_) => Thread.sleep(300) } onThreads tp, // this thread is blocked by sleeping
      &{ case g2(_, r) => r(1) } onThreads tp // we will use this to test whether the entire thread pool is blocked
    )
    g2() shouldEqual 1 // this should initially work
    d() // Now the first reaction is sleeping.
    waitSome()
    g2(timeout = 1000000L*150)() shouldEqual None // this should be blocked now
    tp.shutdownNow()
  }

  it should "not block the cached threadpool with blocking(Thread.sleep)" in {
    val d = new M[Unit]("d")
    val g2 = new B[Unit,Int]("g2")
    val tp = new CachedPool(1)
    join(
      &{ case d(_) => blocking {Thread.sleep(300)} } onThreads tp, // this thread is blocked by sleeping
      &{ case g2(_, r) => r(1) } onThreads tp // we will use this to test whether the entire thread pool is blocked
    )
    g2() shouldEqual 1 // this should initially work
    d() // Now the first reaction is sleeping, but this should not block the thread pool since we use "blocking".
    waitSome()
    g2(timeout = 1000000L*150)() shouldEqual None // this should be blocked
    tp.shutdownNow()
  }

}
