package code.winitzki.jc

import JoinRun._
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.concurrent.Waiters.Waiter
import org.scalatest.time.{Millis, Span}
import org.scalatest.{FlatSpec, Matchers}

class JoinRunSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(500, Millis)

  val warmupTimeMs = 50

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

  behavior of "join definition"

  it should "define a reaction with correct inputs" in {
    val a = ja[Unit]("a")
    val b = ja[Unit]("b")
    val c = ja[Unit]("c")

    a.toString shouldEqual "a"

    join(&{ case a(_) + b(_) + c(_) => })
    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"

    a()
    a()
    b()
    waitSome()
    waitSome()
    a.logSoup shouldEqual "Join{a + b + c => ...}\nMolecules: a() * 2, b()"
  }

  it should "define a reaction with correct inputs with non-default pattern-matching at end of reaction" in {
    val a = ja[Option[Int]]("a")
    val b = ja[Unit]("b")
    val c = ja[Unit]("c")

    join(&{ case b(_) + c(_) + a(Some(x)) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with non-default pattern-matching in the middle of reaction" in {
    val a = ja[Option[Int]]("a")
    val b = ja[Unit]("b")
    val c = ja[Unit]("c")

    join(&{ case b(_) + a(Some(x)) + c(_) => })

    a.logSoup shouldEqual "Join{a + b => ...}\nNo molecules"  // this is the wrong result
    // when the problem is fixed, this test will have to be rewritten
  }

  it should "define a reaction with correct inputs with default pattern-matching in the middle of reaction" in {
    val a = ja[Option[Int]]("a")
    val b = ja[Unit]("b")
    val c = ja[Unit]("c")

    join(&{ case b(_) + a(None) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with non-simple default pattern-matching in the middle of reaction" in {
    val a = ja[Seq[Int]]("a")
    val b = ja[Unit]("b")
    val c = ja[Unit]("c")

    join(&{ case b(_) + a(List()) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with empty option pattern-matching at start of reaction" in {
    val a = ja[Option[Int]]("a")
    val b = ja[Unit]("b")
    val c = ja[Unit]("c")

    join(&{ case a(None) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with constant default pattern-matching at start of reaction" in {
    val a = ja[Int]("a")
    val b = ja[Unit]("b")
    val c = ja[Unit]("c")

    join(&{ case a(0) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with constant non-default pattern-matching at start of reaction" in {
    val a = ja[Int]("a")
    val b = ja[Unit]("b")
    val c = ja[Unit]("c")

    join(&{ case a(1) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a => ...}\nNo molecules" // this is the wrong result
    // when the problem is fixed, this test will have to be rewritten
  }

  it should "define a reaction with correct inputs with constant default option pattern-matching at start of reaction" in {
    val a = ja[Option[Int]]("a")
    val b = ja[Unit]("b")
    val c = ja[Unit]("c")

    join(&{ case a(None) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with constant non-default pattern-matching at end of reaction" in {
    val a = ja[Int]("a")
    val b = ja[Unit]("b")
    val c = ja[Unit]("c")

    join(&{ case b(_) + c(_) + a(1) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with non-default pattern-matching at start of reaction" in {
    val a = ja[Option[Int]]("a")
    val b = ja[Unit]("b")
    val c = ja[Unit]("c")

    join(&{ case a(Some(x)) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a => ...}\nNo molecules" // this is the wrong result
    // when the problem is fixed, this test will have to be rewritten
  }

  it should "generate an error message when the inputs are incorrectly inferred from reaction" in {
    val a = ja[Option[Int]]("a")
    val b = ja[Unit]("b")

    join(&{ case a(Some(x)) + b(_) => }) // currently, the limitations in the pattern-macher will cause this
    // to fail to recognize that "b" is an input molecule in this reaction.

    // when the problem is fixed, this test will have to be rewritten

    a(Some(1))
    val thrown = intercept[Exception] {
      b()
    }
    thrown.getMessage shouldEqual "Molecule b does not belong to any join definition"

  }

  it should "start a simple reaction with one input, defining the injector explicitly" in {

    val waiter = new Waiter

    val a = new JA[Unit]

    join( &{ case a(_) => waiter.dismiss() })

    a()
    waiter.await()
  }

  it should "start a simple reaction with one input" in {

    val waiter = new Waiter

    val a = ja[Unit]
    join( &{ case a(_) => waiter.dismiss() })
    a()
    waiter.await()
  }

  it should "start a simple reaction chain" in {

    val waiter = new Waiter

    val a = ja[Unit]("a")
    val b = ja[Unit]("b")
    join( &{ case a(_) => b() }, &{ case b(_) => waiter.dismiss() })

    a()
    waiter.await()
  }

  it should "start a simple reaction chain with two inputs with values" in {

    val waiter = new Waiter

    val a = ja[Int]
    val b = ja[Int]
    val c = ja[Int]
    join( &{ case a(x) + b(y) => c(x+y) }, &{ case c(z) => waiter { z shouldEqual 3 }; waiter.dismiss() })
    a(1)
    b(2)
    waiter.await()
  }

  it should "throw exception when join pattern is nonlinear" in {
    val thrown = intercept[Exception] {
      val a = ja[Unit]("a")
      join( &{ case a(_) + a(_) => () })
      a()
    }
    thrown.getMessage shouldEqual "Nonlinear pattern: a used twice"

  }

  it should "throw exception when join pattern is nonlinear, with blocking molecule" in {
    val thrown = intercept[Exception] {
      val a = js[Unit,Unit]("a")
      join( &{ case a(_,r) + a(_,s) => () })
      a()
    }
    thrown.getMessage shouldEqual "Nonlinear pattern: a/S used twice"
  }

  it should "throw exception when join pattern attempts to redefine a blocking molecule" in {
    val thrown = intercept[Exception] {
      val a = js[Unit,Unit]("a")
      join( &{ case a(_,_) => () })
      join( &{ case a(_,_) => () })
    }
    thrown.getMessage shouldEqual "Molecule a/S cannot be used as input since it was already used in Join{a/S => ...}"
  }

  it should "throw exception when join pattern attempts to redefine a non-blocking molecule" in {
    val thrown = intercept[Exception] {
      val a = ja[Unit]("x")
      val b = ja[Unit]("y")
      join( &{ case a(_) + b(_) => () })
      join( &{ case a(_) => () })
    }
    thrown.getMessage shouldEqual "Molecule x cannot be used as input since it was already used in Join{x + y => ...}"
  }

  it should "throw exception when trying to inject a blocking molecule that has no join" in {
    val thrown = intercept[Exception] {
      val a = js[Unit,Unit]("x")
      a()
    }
    thrown.getMessage shouldEqual "Molecule x/S does not belong to any join definition"
  }

  it should "throw exception when trying to inject a non-blocking molecule that has no join" in {
    val thrown = intercept[Exception] {
      val a = ja[Unit]("x")
      a()
    }
    thrown.getMessage shouldEqual "Molecule x does not belong to any join definition"
  }

  it should "throw exception when trying to log soup of a blocking molecule that has no join" in {
    val thrown = intercept[Exception] {
      val a = js[Unit,Unit]("x")
      a.logSoup
    }
    thrown.getMessage shouldEqual "Molecule x/S does not belong to any join definition"
  }

  it should "throw exception when trying to log soup a non-blocking molecule that has no join" in {
    val thrown = intercept[Exception] {
      val a = ja[Unit]("x")
      a.logSoup
    }
    thrown.getMessage shouldEqual "Molecule x does not belong to any join definition"
  }

  it should "fail to start reactions when pattern is not matched" in {

    val a = ja[Int]
    val b = ja[Int]
    val f = js[Unit,Int]

    join( &{ case a(x) + b(0) => a(x+1) }, &{ case a(z) + f(_, r) => r(z) })
    a(1)
    b(2)
    waitSome()
    f() shouldEqual 1
  }

  it should "implement the non-blocking single-access counter" in {
    val c = ja[Int]("c")
    val d = ja[Unit]("decrement")
    val g = js[Unit,Int]("getValue")
    join(
      &{ case c(n) + d(_) => c(n-1) },
      &{ case c(n) + g(_,r) => c(n) + r(n) }
    )
    c(2) + d() + d()
    waitSome()
    g() shouldEqual 0

  }

  it should "use one thread for concurrent computations" in {
    val c = ja[Int]("counter")
    val d = ja[Unit]("decrement")
    val f = ja[Unit]("finished")
    val a = ja[Int]("all_finished")
    val g = js[Unit,Int]("getValue")

    val tp = new ReactionPool(1)

    join(
      &{ case c(x) + d(_) => Thread.sleep(100); c(x-1) + f() } onThreads tp,
      &{ case a(x) + g(_, r) => a(x) + r(x) },
      &{ case f(_) + a(x) => a(x+1) }
    )
    a(0) + c(1) + c(1) + d() + d()
    Thread.sleep(150) // This is less than 200ms, so we have not yet finished the second computation.
    g() shouldEqual 1
    Thread.sleep(150) // Now we should have finished the second computation.
    g() shouldEqual 2

    tp.shutdownNow()
  }

  it should "use two threads for concurrent computations" in {
    val c = ja[Int]("counter")
    val d = ja[Unit]("decrement")
    val f = ja[Unit]("finished")
    val a = ja[Int]("all_finished")
    val g = js[Unit,Int]("getValue")

    val tp = new ReactionPool(2)

    join(
      &{ case c(x) + d(_) => Thread.sleep(100); c(x-1) + f() } onThreads tp,
      &{ case a(x) + g(_, r) => r(x) },
      &{ case f(_) + a(x) => a(x+1) }
    )
    a(0) + c(1) + c(1) + d() + d()
    Thread.sleep(150) // This is less than 200ms, and the test fails unless we use 2 threads concurrently.
    g() shouldEqual 2

    tp.shutdownNow()
  }

  it should "process simple reactions quickly enough" in {
    val n = 2000

    val c = ja[Int]("counter")
    val d = ja[Unit]("decrement")
    val g = js[Unit, Int]("getValue")
    val tp = new ReactionPool(2)
    join(
      & { case c(x) + d(_) => c(x - 1) } onThreads tp,
      & { case c(x) + g(_, r) => c(x) + r(x) }
    )
    c(n)
    (1 to n).foreach { _ => d() }

    Thread.sleep(400)
    g() shouldEqual 0

    tp.shutdownNow()
  }

  it should "resume fault-tolerant reactions by retrying even if processes crash with fixed probability" in {
    val n = 20

    val probabilityOfCrash = 0.5

    val c = ja[Int]("counter")
    val d = ja[Unit]("decrement")
    val g = js[Unit, Int]("getValue")
    val tp = new ReactionPool(2)

    join(
      & { case c(x) + d(_) =>
        if (scala.util.Random.nextDouble >= probabilityOfCrash) c(x - 1) else throw new Exception("crash! (it's OK, ignore this)")
      }.withRetry onThreads tp,
      & { case c(x) + g(_, r) => c(x) + r(x) }
    )
    c(n)
    (1 to n).foreach { _ => d() }

    waitSome()
    Thread.sleep(200) // give it some more time to compensate for crashes
    g() shouldEqual 0

    tp.shutdownNow()
  }

}
