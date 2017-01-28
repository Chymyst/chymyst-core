package io.chymyst.test

import io.chymyst.jc._
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.concurrent.Waiters.{PatienceConfig, Waiter}
import org.scalatest.time.{Millis, Span}
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

import scala.concurrent.duration._
import scala.language.postfixOps

class MoleculesSpec extends FlatSpec with Matchers with TimeLimitedTests with BeforeAndAfterEach {

  var tp0: Pool = _

  implicit val patienceConfig = PatienceConfig(timeout = Span(500, Millis))

  override def beforeEach(): Unit = {
    tp0 = new FixedPool(4)
  }

  override def afterEach(): Unit = {
    tp0.shutdownNow()
  }
  
  val timeLimit = Span(5000, Millis)

  val warmupTimeMs = 50L

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

  behavior of "reaction site"

  it should "define a reaction with correct inputs" in {
    val a = m[Unit]
    val b = m[Unit]
    val c = m[Unit]

    site(tp0)(go { case a(_) + b(_) + c(_) => })
    a.logSoup shouldEqual "Site{a + b + c => ...}\nNo molecules"

  }

  it should "correctly list molecules present in soup" in {
    val a = m[Unit]
    val bb = m[Unit]
    val c = m[Unit]
    val d = m[Unit]
    val f = b[Unit, Unit]
    val g = b[Unit, Unit]

    site(tp0)(
      go { case g(_, r) + d(_) => r() },
      go { case a(_) + bb(_) + c(_) + f(_, r) => r() }
    )
    a.logSoup shouldEqual "Site{a + bb + c + f/B => ...; d + g/B => ...}\nNo molecules"

    a()
    a()
    bb()
    Thread.sleep(100)
    d()
    g.timeout()(1.second) shouldEqual Some(())
    a.logSoup shouldEqual "Site{a + bb + c + f/B => ...; d + g/B => ...}\nMolecules: a() * 2, bb()"
    c()
    f.timeout()(1.second) shouldEqual Some(())
    a.logSoup shouldEqual "Site{a + bb + c + f/B => ...; d + g/B => ...}\nMolecules: a()"
  }

  it should "define a reaction with correct inputs with non-default pattern-matching at end of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    site(go { case b(_) + c(_) + a(Some(x)) => })

    a.logSoup shouldEqual "Site{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with zero default pattern-matching at start of reaction" in {
    val a = new M[Int]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    site(go { case a(0) + b(_) + c(_) => })

    a.logSoup shouldEqual "Site{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with constant non-default pattern-matching at end of reaction" in {
    val a = new M[Int]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    site(go { case b(_) + c(_) + a(1) => })

    a.logSoup shouldEqual "Site{a + b + c => ...}\nNo molecules"
  }

  it should "start a simple reaction with one input, defining the emitter explicitly" in {

    val waiter = new Waiter

    val a = new M[Unit]("a")
    site(tp0)( go { case a(_) => waiter.dismiss() })

    a()
    waiter.await()
  }

  it should "start a simple reaction with one input" in {

    val waiter = new Waiter

    val a = new M[Unit]("a")
    site(tp0)( go { case a(_) => waiter.dismiss() })

    a()
    waiter.await()
  }

  it should "start a simple reaction chain" in {

    val waiter = new Waiter

    val a = new M[Unit]("a")
    val b = new M[Unit]("b")
    site(tp0)( go { case a(_) => b() }, go { case b(_) => waiter.dismiss() })

    a()
    waiter.await()
  }

  it should "start a simple reaction chain with two inputs with values" in {

    val waiter = new Waiter

    val a = new M[Int]("a")
    val b = new M[Int]("b")
    val c = new M[Int]("c")
    site(tp0)( go { case a(x) + b(y) => c(x+y) }, go { case c(z) => waiter { z shouldEqual 3; () }; waiter.dismiss() })
    a(1)
    b(2)
    waiter.await()
  }

  it should "accept nonlinear input patterns" in {
    val a = new M[Unit]("a")
    site(go { case a(_) + a(_) => () })
  }

  it should "accept nonlinear input patterns, with blocking molecule" in {
    val a = new B[Unit, Unit]("a")
    site(go { case a(_, r) + a(_, s) => r() + s() })
  }

  it should "throw exception when join pattern attempts to redefine a blocking molecule" in {
    val thrown = intercept[Exception] {
      val a = new B[Unit, Unit]("a")
      site( go { case a(_,r) => r() })
      site( go { case a(_,r) => r() })
    }
    thrown.getMessage shouldEqual "Molecule a/B cannot be used as input in Site{a/B => ...} since it is already bound to Site{a/B => ...}"
  }

  it should "throw exception when join pattern attempts to redefine a non-blocking molecule" in {
    val thrown = intercept[Exception] {
      val a = new M[Unit]("x")
      val b = new M[Unit]("y")
      site( go { case a(_) + b(_) => () })
      site( go { case a(_) => () })
    }
    thrown.getMessage shouldEqual "Molecule x cannot be used as input in Site{x => ...} since it is already bound to Site{x + y => ...}"
  }

  it should "throw exception when trying to emit a blocking molecule that has no join" in {
    val thrown = intercept[Exception] {
      val a = new B[Unit, Unit]("x")
      a()
    }
    thrown.getMessage shouldEqual "Molecule x/B is not bound to any reaction site"
  }

  it should "throw exception when trying to emit a non-blocking molecule that has no join" in {
    val thrown = intercept[Exception] {
      val a = new M[Unit]("x")
      a()
    }
    thrown.getMessage shouldEqual "Molecule x is not bound to any reaction site"
  }

  it should "throw exception when trying to log soup of a blocking molecule that has no join" in {
    val thrown = intercept[Exception] {
      val a = new B[Unit, Unit]("x")
      a.logSoup
    }
    thrown.getMessage shouldEqual "Molecule x/B is not bound to any reaction site"
  }

  it should "throw exception when trying to log soup a non-blocking molecule that has no join" in {
    val thrown = intercept[Exception] {
      val a = new M[Unit]("x")
      a.logSoup
    }
    thrown.getMessage shouldEqual "Molecule x is not bound to any reaction site"
  }

  it should "fail to start reactions when pattern is not matched" in {

    val a = new M[Int]("a")
    val b = new M[Int]("b")
    val f = new B[Unit, Int]("f")

    site(tp0)( go { case a(x) + b(0) => a(x+1) }, go { case a(z) + f(_, r) => r(z) })
    a(1)
    b(2)
    f() shouldEqual 1
  }

  it should "fail to start reactions when unbound molecule is emitted by reactions" in {

    val a = m[Int]
    val b = m[Int]

    site(tp0)( go { case a(x) => b(x) })
    val thrown = intercept[Exception] {
      a(1)
    }
    thrown.getMessage shouldEqual "In Site{a => ...}: Some reactions may emit molecules (b) that are not bound to any reaction site"
  }

  it should "fail to start reactions when several unbound molecules are emitted by reactions" in {
    val a = m[Int]
    val a1 = m[Int]
    val a2 = m[Int]
    val b = m[Int]
    val c = m[Unit]

    site(tp0)( go { case a(x) => }, go { case a1(x) => c() + c() }, go { case a2(x) => c() + b(x) })
    val thrown = intercept[Exception] {
      a(1) // This molecule will not actually cause any reactions that would emit unbound molecules.
      // Nevertheless, the error must be flagged.
    }
    thrown.getMessage shouldEqual "In Site{a => ...; a1 => ...; a2 => ...}: Some reactions may emit molecules (b, c) that are not bound to any reaction site"
  }

  behavior of "basic functionality"

  it should "implement the non-blocking single-access counter" in {
    val c = new M[Int]("c")
    val d = new M[Unit]("decrement")
    val g = new B[Unit, Int]("getValue")
    site(tp0)(
      go { case c(n) + d(_) => c(n-1) },
      go { case c(0) + g(_,r) => r(0) }
    )
    c(2) + d() + d()
    g.timeout()(1.second) shouldEqual Some(0)
  }

  it should "use only one thread for concurrent computations" in {
    val c = new M[Int]("counter")
    val d = new M[Unit]("decrement")
    val f = new M[Unit]("finished")
    val a = new M[Int]("all_finished")
    val g = new B[Unit, Int]("getValue")

    val tp = new FixedPool(1)

    site(tp0)(
      go { case c(x) + d(_) => Thread.sleep(300); c(x-1) + f() } onThreads tp,
      go { case a(x) + g(_, r) => a(x); r(x) },
      go { case f(_) + a(x) => a(x+1) }
    )
    a(0) + c(1) + c(1) + d() + d()
    g.timeout()(1.second) shouldEqual Some(0)
    Thread.sleep(150) // This is less than 300ms, so we have not yet finished the first computation.
    g.timeout()(1.second) shouldEqual Some(0)
    Thread.sleep(300) // Now we should have finished the first computation.
    g.timeout()(1.second) shouldEqual Some(1)
    Thread.sleep(300) // Now we should have finished the second computation.
    g.timeout()(1.second) shouldEqual Some(2)

    tp.shutdownNow()
  }

  // this test sometimes fails
  it should "use two threads for concurrent computations" in {
    val c = new M[Unit]("counter")
    val d = new M[Unit]("decrement")
    val f = new M[Unit]("finished")
    val a = new M[Int]("all_finished")
    val g = new B[Unit, Int]("getValue")

    val tp = new FixedPool(2)

    site(tp0)(
      go { case c(_) + d(_) => Thread.sleep(300); f() } onThreads tp,
      go { case a(x) + g(_, r) => r(x) },
      go { case f(_) + a(x) => a(x+1) }
    )
    a(0) + c() + c() + d() + d()
    Thread.sleep(500) // This is less than 2*300ms, and the test fails unless we use 2 threads concurrently.
    g.timeout()(1.second) shouldEqual Some(2)

    tp.shutdownNow()
  }

  behavior of "fault-tolerant resume facility"

  it should "fail to finish job if 1 out of 2 processes crash while retry is not set" in {
    val n = 20

    val probabilityOfCrash = 0.5

    val c = new M[Int]("counter")
    val d = new M[Unit]("decrement")
    val g = new B[Unit, Unit]("getValue")
    val tp = new FixedPool(2)

    site(tp0)(
      go  { case c(x) + d(_) =>
        if (scala.util.Random.nextDouble >= probabilityOfCrash) c(x - 1) else throw new Exception("crash! (it's OK, ignore this)")
      }.noRetry onThreads tp,
      go  { case c(0) + g(_, r) => r() }
    )
    c(n)
    (1 to n).foreach { _ => d() }

    val result = g.timeout()(1500 millis)
    tp.shutdownNow()
    result shouldEqual None
  }

  it should "finish job by retrying reactions even if 1 out of 2 processes crash" in {
    val n = 20

    val probabilityOfCrash = 0.5

    val c = new M[Int]("counter")
    val d = new M[Unit]("decrement")
    val g = new B[Unit, Unit]("getValue")
    val tp = new FixedPool(2)

    site(tp0)(
      go  { case c(x) + d(_) =>
        if (scala.util.Random.nextDouble >= probabilityOfCrash) c(x - 1) else throw new Exception("crash! (it's OK, ignore this)")
      }.withRetry onThreads tp,
      go  { case c(0) + g(_, r) => r() }
    )
    c(n)
    (1 to n).foreach { _ => d() }

    val result = g.timeout()(1500 millis)
    tp.shutdownNow()
    result shouldEqual Some(())
  }

  it should "retry reactions that contain blocking molecules" in {
    val n = 20

    val probabilityOfCrash = 0.5

    val c = new M[Int]("counter")
    val d = new B[Unit, Unit]("decrement")
    val g = new B[Unit, Unit]("getValue")
    val tp = new FixedPool(2)

    site(tp0)(
      go  { case c(x) + d(_, r) =>
        if (scala.util.Random.nextDouble >= probabilityOfCrash) { c(x - 1); r() } else { throw new Exception("crash! (it's OK, ignore this)"); r() }
      }.withRetry onThreads tp,
      go  { case c(0) + g(_, r) => r() }
    )
    c(n)
    (1 to n).foreach { _ =>
      if (d.timeout()(1500 millis).isEmpty) {
        println(globalErrorLog.take(50).toList) // this should not happen, but will be helpful for debugging
      }
    }

    val result = g.timeout()(1500 millis)
    globalErrorLog.exists(_.contains("Message: crash! (it's OK, ignore this)"))
    tp.shutdownNow()
    result shouldEqual Some(())
  }

}
