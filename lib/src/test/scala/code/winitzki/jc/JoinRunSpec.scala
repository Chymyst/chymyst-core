package code.winitzki.jc

import JoinRun._
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.concurrent.Waiters.Waiter
import org.scalatest.time.{Millis, Span}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpec, Matchers}

class JoinRunSpec extends FlatSpec with Matchers with TimeLimitedTests with BeforeAndAfterEach {

  var tp0: Pool = null

  override def beforeEach(): Unit = {
    tp0 = new FixedPool(4)
  }

  override def afterEach(): Unit = {
    tp0.shutdownNow()
  }
  
  val timeLimit = Span(1000, Millis)

  val warmupTimeMs = 50

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

  behavior of "join definition"

  it should "define a reaction with correct inputs" in {
    val a = new M[Unit]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    a.toString shouldEqual "a"

    join(tp0)(runSimple { case a(_) + b(_) + c(_) => })
    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"

    a()
    a()
    b()
    waitSome()
    waitSome()
    a.logSoup shouldEqual "Join{a + b + c => ...}\nMolecules: a() * 2, b()"
  }

  it should "define a reaction with correct inputs with non-default pattern-matching at end of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(runSimple { case b(_) + c(_) + a(Some(x)) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with zero default pattern-matching at start of reaction" in {
    val a = new M[Int]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(runSimple { case a(0) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with constant non-default pattern-matching at end of reaction" in {
    val a = new M[Int]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(runSimple { case b(_) + c(_) + a(1) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "start a simple reaction with one input, defining the injector explicitly" in {

    val waiter = new Waiter

    val a = new M[Unit]("a")
    join(tp0)( runSimple { case a(_) => waiter.dismiss() })

    a()
    waiter.await()
  }

  it should "start a simple reaction with one input" in {

    val waiter = new Waiter

    val a = new M[Unit]("a")
    join(tp0)( runSimple { case a(_) => waiter.dismiss() })

    a()
    waiter.await()
  }

  it should "start a simple reaction chain" in {

    val waiter = new Waiter

    val a = new M[Unit]("a")
    val b = new M[Unit]("b")
    join(tp0)( runSimple { case a(_) => b() }, runSimple { case b(_) => waiter.dismiss() })

    a()
    waiter.await()
  }

  it should "start a simple reaction chain with two inputs with values" in {

    val waiter = new Waiter

    val a = new M[Int]("a")
    val b = new M[Int]("b")
    val c = new M[Int]("c")
    join(tp0)( runSimple { case a(x) + b(y) => c(x+y) }, runSimple { case c(z) => waiter { z shouldEqual 3 }; waiter.dismiss() })
    a(1)
    b(2)
    waiter.await()
  }

  it should "throw exception when join pattern is nonlinear" in {
    val thrown = intercept[Exception] {
      val a = new M[Unit]("a")
      join(tp0)( runSimple { case a(_) + a(_) => () })
      a()
    }
    thrown.getMessage shouldEqual "Nonlinear pattern: a used twice"

  }

  it should "throw exception when join pattern is nonlinear, with blocking molecule" in {
    val thrown = intercept[Exception] {
      val a = new B[Unit,Unit]("a")
      join(tp0)( runSimple { case a(_,r) + a(_,s) => () })
      a()
    }
    thrown.getMessage shouldEqual "Nonlinear pattern: a/B used twice"
  }

  it should "throw exception when join pattern attempts to redefine a blocking molecule" in {
    val thrown = intercept[Exception] {
      val a = new B[Unit,Unit]("a")
      join( runSimple { case a(_,_) => () })
      join( runSimple { case a(_,_) => () })
    }
    thrown.getMessage shouldEqual "Molecule a/B cannot be used as input since it is already bound to Join{a/B => ...}"
  }

  it should "throw exception when join pattern attempts to redefine a non-blocking molecule" in {
    val thrown = intercept[Exception] {
      val a = new M[Unit]("x")
      val b = new M[Unit]("y")
      join( runSimple { case a(_) + b(_) => () })
      join( runSimple { case a(_) => () })
    }
    thrown.getMessage shouldEqual "Molecule x cannot be used as input since it is already bound to Join{x + y => ...}"
  }

  it should "throw exception when trying to inject a blocking molecule that has no join" in {
    val thrown = intercept[Exception] {
      val a = new B[Unit,Unit]("x")
      a()
    }
    thrown.getMessage shouldEqual "Molecule x/B is not bound to any join definition"
  }

  it should "throw exception when trying to inject a non-blocking molecule that has no join" in {
    val thrown = intercept[Exception] {
      val a = new M[Unit]("x")
      a()
    }
    thrown.getMessage shouldEqual "Molecule x is not bound to any join definition"
  }

  it should "throw exception when trying to log soup of a blocking molecule that has no join" in {
    val thrown = intercept[Exception] {
      val a = new B[Unit,Unit]("x")
      a.logSoup
    }
    thrown.getMessage shouldEqual "Molecule x/B is not bound to any join definition"
  }

  it should "throw exception when trying to log soup a non-blocking molecule that has no join" in {
    val thrown = intercept[Exception] {
      val a = new M[Unit]("x")
      a.logSoup
    }
    thrown.getMessage shouldEqual "Molecule x is not bound to any join definition"
  }

  it should "fail to start reactions when pattern is not matched" in {

    val a = new M[Int]("a")
    val b = new M[Int]("b")
    val f = new B[Unit,Int]("f")

    join(tp0)( runSimple { case a(x) + b(0) => a(x+1) }, runSimple { case a(z) + f(_, r) => r(z) })
    a(1)
    b(2)
    waitSome()
    f() shouldEqual 1
  }

  it should "implement the non-blocking single-access counter" in {
    val c = new M[Int]("c")
    val d = new M[Unit]("decrement")
    val g = new B[Unit,Int]("getValue")
    join(tp0)(
      runSimple { case c(n) + d(_) => c(n-1) },
      runSimple { case c(n) + g(_,r) => c(n) + r(n) }
    )
    c(2) + d() + d()
    waitSome()
    g() shouldEqual 0
  }

  it should "use only one thread for concurrent computations" in {
    val c = new M[Int]("counter")
    val d = new M[Unit]("decrement")
    val f = new M[Unit]("finished")
    val a = new M[Int]("all_finished")
    val g = new B[Unit,Int]("getValue")

    val tp = new FixedPool(1)

    join(tp0)(
      runSimple { case c(x) + d(_) => Thread.sleep(300); c(x-1) + f() } onThreads tp,
      runSimple { case a(x) + g(_, r) => a(x) + r(x) },
      runSimple { case f(_) + a(x) => a(x+1) }
    )
    a(0) + c(1) + c(1) + d() + d()
    g() shouldEqual 0
    Thread.sleep(150) // This is less than 300ms, so we have not yet finished the first computation.
    g() shouldEqual 0
    Thread.sleep(300) // Now we should have finished the first computation.
    g() shouldEqual 1
    Thread.sleep(300) // Now we should have finished the second computation.
    g() shouldEqual 2

    tp.shutdownNow()
  }

  it should "use two threads for concurrent computations" in {
    val c = new M[Unit]("counter")
    val d = new M[Unit]("decrement")
    val f = new M[Unit]("finished")
    val a = new M[Int]("all_finished")
    val g = new B[Unit,Int]("getValue")

    val tp = new FixedPool(2)

    join(tp0)(
      runSimple { case c(_) + d(_) => Thread.sleep(200); f() } onThreads tp,
      runSimple { case a(x) + g(_, r) => r(x) },
      runSimple { case f(_) + a(x) => a(x+1) }
    )
    a(0) + c() + c() + d() + d()
    Thread.sleep(300) // This is less than 2*200ms, and the test fails unless we use 2 threads concurrently.
    g() shouldEqual 2

    tp.shutdownNow()
  }

  it should "process simple reactions quickly enough" in {
    val n = 1000

    val c = new M[Int]("counter")
    val d = new M[Unit]("decrement")
    val g = new B[Unit, Int]("getValue")
    val tp = new FixedPool(2)
    join(tp0)(
      runSimple  { case c(x) + d(_) => c(x - 1) } onThreads tp,
      runSimple  { case c(0) + g(_, r) => c(0) + r(0) }
    )
    c(n)
    (1 to n).foreach { _ => d() }

    g() shouldEqual 0

    tp.shutdownNow()
  }

  it should "resume fault-tolerant reactions by retrying even if processes crash with fixed probability" in {
    val n = 20

    val probabilityOfCrash = 0.5

    val c = new M[Int]("counter")
    val d = new M[Unit]("decrement")
    val g = new B[Unit, Int]("getValue")
    val tp = new FixedPool(2)

    join(tp0)(
      runSimple  { case c(x) + d(_) =>
        if (scala.util.Random.nextDouble >= probabilityOfCrash) c(x - 1) else throw new Exception("crash! (it's OK, ignore this)")
      }.withRetry onThreads tp,
      runSimple  { case c(x) + g(_, r) => c(x) + r(x) }
    )
    c(n)
    (1 to n).foreach { _ => d() }

    waitSome()
    Thread.sleep(200) // give it some more time to compensate for crashes
    g() shouldEqual 0

    tp.shutdownNow()
  }

}
