package code.chymyst.jc

import Core._

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

  it should "track whether molecule emitters are bound" in {
    val a = new E("a123")
    val b = new E("b")
    val c = new E("")

    a.toString shouldEqual "a123"
    b.toString shouldEqual "b"
    c.toString shouldEqual "<no name>"

    a.isBound shouldEqual false
    b.isBound shouldEqual false
    c.isBound shouldEqual false

    site(_go { case a(_) + c(_) => b() })

    a.isBound shouldEqual true
    b.isBound shouldEqual false
    c.isBound shouldEqual true

    a.emittingReactions shouldEqual Set()
    b.emittingReactions shouldEqual Set() // we don't use macros here, so we don't know which molecules are emitted as output
    c.emittingReactions shouldEqual Set()
    a.consumingReactions.get.size shouldEqual 1
    a.consumingReactions.get.head.toString shouldEqual "<no name> + a123 => ..."
    b.consumingReactions shouldEqual None
    c.consumingReactions.get shouldEqual a.consumingReactions.get
  }

  it should "define a reaction with correct inputs" in {
    val a = new E("a")
    val b = new E("b")
    val c = new E("c")

    site(tp0)(_go { case a(_) + b(_) + c(_) => })
    a.logSoup shouldEqual "Site{a + b + c => ...}\nNo molecules"

  }

  it should "correctly list molecules present in soup" in {
    val a = new E("a")
    val b = new E("b")
    val c = new E("c")
    val f = new EE("f")

    site(tp0)(
      _go { case a(_) + b(_) + c(_) + f(_, r) => r() }
    )
    a.logSoup shouldEqual "Site{a + b + c + f/B => ...}\nNo molecules"

    a()
    a()
    b()
    Thread.sleep(400)
    a.logSoup shouldEqual "Site{a + b + c + f/B => ...}\nMolecules: a() * 2, b()"
    c()
    f()
    a.logSoup shouldEqual "Site{a + b + c + f/B => ...}\nMolecules: a()"
  }

  it should "define a reaction with correct inputs with non-default pattern-matching at end of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new E("b")
    val c = new E("c")

    site(_go { case b(_) + c(_) + a(Some(x)) => })

    a.logSoup shouldEqual "Site{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with zero default pattern-matching at start of reaction" in {
    val a = new M[Int]("a")
    val b = new E("b")
    val c = new E("c")

    site(_go { case a(0) + b(_) + c(_) => })

    a.logSoup shouldEqual "Site{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with constant non-default pattern-matching at end of reaction" in {
    val a = new M[Int]("a")
    val b = new E("b")
    val c = new E("c")

    site(_go { case b(_) + c(_) + a(1) => })

    a.logSoup shouldEqual "Site{a + b + c => ...}\nNo molecules"
  }

  it should "start a simple reaction with one input, defining the emitter explicitly" in {

    val waiter = new Waiter

    val a = new E("a")
    site(tp0)( _go { case a(_) => waiter.dismiss() })

    a()
    waiter.await()
  }

  it should "start a simple reaction with one input" in {

    val waiter = new Waiter

    val a = new E("a")
    site(tp0)( _go { case a(_) => waiter.dismiss() })

    a()
    waiter.await()
  }

  it should "start a simple reaction chain" in {

    val waiter = new Waiter

    val a = new E("a")
    val b = new E("b")
    site(tp0)( _go { case a(_) => b() }, _go { case b(_) => waiter.dismiss() })

    a()
    waiter.await()
  }

  it should "start a simple reaction chain with two inputs with values" in {

    val waiter = new Waiter

    val a = new M[Int]("a")
    val b = new M[Int]("b")
    val c = new M[Int]("c")
    site(tp0)( _go { case a(x) + b(y) => c(x+y) }, _go { case c(z) => waiter { z shouldEqual 3; () }; waiter.dismiss() })
    a(1)
    b(2)
    waiter.await()
  }

  it should "throw exception when join pattern is nonlinear" in {
    val thrown = intercept[Exception] {
      val a = new E("a")
      site( _go { case a(_) + a(_) => () })
    }
    thrown.getMessage shouldEqual "Nonlinear pattern: a used twice"

  }

  it should "throw exception when join pattern is nonlinear, with blocking molecule" in {
    val thrown = intercept[Exception] {
      val a = new EE("a")
      site( _go { case a(_,r) + a(_,s) => () })
    }
    thrown.getMessage shouldEqual "Nonlinear pattern: a/B used twice"
  }

  it should "throw exception when join pattern attempts to redefine a blocking molecule" in {
    val thrown = intercept[Exception] {
      val a = new EE("a")
      site( _go { case a(_,_) => () })
      site( _go { case a(_,_) => () })
    }
    thrown.getMessage shouldEqual "Molecule a/B cannot be used as input in Site{a/B => ...} since it is already bound to Site{a/B => ...}"
  }

  it should "throw exception when join pattern attempts to redefine a non-blocking molecule" in {
    val thrown = intercept[Exception] {
      val a = new E("x")
      val b = new E("y")
      site( _go { case a(_) + b(_) => () })
      site( _go { case a(_) => () })
    }
    thrown.getMessage shouldEqual "Molecule x cannot be used as input in Site{x => ...} since it is already bound to Site{x + y => ...}"
  }

  it should "throw exception when trying to emit a blocking molecule that has no join" in {
    val thrown = intercept[Exception] {
      val a = new EE("x")
      a()
    }
    thrown.getMessage shouldEqual "Molecule x/B is not bound to any reaction site"
  }

  it should "throw exception when trying to emit a non-blocking molecule that has no join" in {
    val thrown = intercept[Exception] {
      val a = new E("x")
      a()
    }
    thrown.getMessage shouldEqual "Molecule x is not bound to any reaction site"
  }

  it should "throw exception when trying to log soup of a blocking molecule that has no join" in {
    val thrown = intercept[Exception] {
      val a = new EE("x")
      a.logSoup
    }
    thrown.getMessage shouldEqual "Molecule x/B is not bound to any reaction site"
  }

  it should "throw exception when trying to log soup a non-blocking molecule that has no join" in {
    val thrown = intercept[Exception] {
      val a = new E("x")
      a.logSoup
    }
    thrown.getMessage shouldEqual "Molecule x is not bound to any reaction site"
  }

  it should "fail to start reactions when pattern is not matched" in {

    val a = new M[Int]("a")
    val b = new M[Int]("b")
    val f = new EB[Int]("f")

    site(tp0)( _go { case a(x) + b(0) => a(x+1) }, _go { case a(z) + f(_, r) => r(z) })
    a(1)
    b(2)
    f() shouldEqual 1
  }

  it should "implement the non-blocking single-access counter" in {
    val c = new M[Int]("c")
    val d = new E("decrement")
    val g = new EB[Int]("getValue")
    site(tp0)(
      _go { case c(n) + d(_) => c(n-1) },
      _go { case c(0) + g(_,r) => r(0) }
    )
    c(2) + d() + d()
    g() shouldEqual 0
  }

  it should "use only one thread for concurrent computations" in {
    val c = new M[Int]("counter")
    val d = new E("decrement")
    val f = new E("finished")
    val a = new M[Int]("all_finished")
    val g = new EB[Int]("getValue")

    val tp = new FixedPool(1)

    site(tp0)(
      _go { case c(x) + d(_) => Thread.sleep(300); c(x-1) + f() } onThreads tp,
      _go { case a(x) + g(_, r) => a(x); r(x) },
      _go { case f(_) + a(x) => a(x+1) }
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

  // this test sometimes fails
  it should "use two threads for concurrent computations" in {
    val c = new E("counter")
    val d = new E("decrement")
    val f = new E("finished")
    val a = new M[Int]("all_finished")
    val g = new EB[Int]("getValue")

    val tp = new FixedPool(2)

    site(tp0)(
      _go { case c(_) + d(_) => Thread.sleep(300); f() } onThreads tp,
      _go { case a(x) + g(_, r) => r(x) },
      _go { case f(_) + a(x) => a(x+1) }
    )
    a(0) + c() + c() + d() + d()
    Thread.sleep(500) // This is less than 2*300ms, and the test fails unless we use 2 threads concurrently.
    g() shouldEqual 2

    tp.shutdownNow()
  }

  it should "fail to finish if 1 out of 2 processes crash, and retry is not set" in {
    val n = 20

    val probabilityOfCrash = 0.5

    val c = new M[Int]("counter")
    val d = new E("decrement")
    val g = new EE("getValue")
    val tp = new FixedPool(2)

    site(tp0)(
      _go  { case c(x) + d(_) =>
        if (scala.util.Random.nextDouble >= probabilityOfCrash) c(x - 1) else throw new Exception("crash! (it's OK, ignore this)")
      }.noRetry onThreads tp,
      _go  { case c(0) + g(_, r) => r() }
    )
    c(n)
    (1 to n).foreach { _ => d() }

    val result = g.timeout(1500 millis)()
    tp.shutdownNow()
    result shouldEqual None
  }

  it should "resume fault-tolerant reactions by retrying even if 1 out of 2 processes crash" in {
    val n = 20

    val probabilityOfCrash = 0.5

    val c = new M[Int]("counter")
    val d = new E("decrement")
    val g = new EE("getValue")
    val tp = new FixedPool(2)

    site(tp0)(
      _go  { case c(x) + d(_) =>
        if (scala.util.Random.nextDouble >= probabilityOfCrash) c(x - 1) else throw new Exception("crash! (it's OK, ignore this)")
      }.withRetry onThreads tp,
      _go  { case c(0) + g(_, r) => r() }
    )
    c(n)
    (1 to n).foreach { _ => d() }

    val result = g.timeout(1500 millis)()
    tp.shutdownNow()
    result shouldEqual Some(())
  }

  it should "resume fault-tolerant reactions that contain blocking molecules" in {
    val n = 20

    val probabilityOfCrash = 0.5

    val c = new M[Int]("counter")
    val d = new EE("decrement")
    val g = new EE("getValue")
    val tp = new FixedPool(2)

    site(tp0)(
      _go  { case c(x) + d(_, r) =>
        if (scala.util.Random.nextDouble >= probabilityOfCrash) { c(x - 1); r() } else throw new Exception("crash! (it's OK, ignore this)")
      }.withRetry onThreads tp,
      _go  { case c(0) + g(_, r) => r() }
    )
    c(n)
    (1 to n).foreach { _ =>
      if (d.timeout(1500 millis)().isEmpty) {
        println(globalErrorLog.toList) // this should not happen, but will be helpful for debugging
      }
    }

    val result = g.timeout(1500 millis)()
    globalErrorLog.exists(_.contains("Message: crash! (it's OK, ignore this)"))
    tp.shutdownNow()
    result shouldEqual Some(())
  }

}
