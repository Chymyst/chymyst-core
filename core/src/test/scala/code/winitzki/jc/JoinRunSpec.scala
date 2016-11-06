package code.winitzki.jc

import JoinRun.{&, +, _}
import org.scalatest.concurrent.Waiters.Waiter
import org.scalatest.{FlatSpec, Matchers}

class JoinRunSpec extends FlatSpec with Matchers {
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
    a.setLogLevel(3)
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

  it should "block for a synchronous molecule" in {

    val a = ja[Unit]
    val f = js[Unit,Int]
    join( &{ case a(_) + f(_, r) => r(3) })
    a()
    a()
    a()
    f() shouldEqual 3
    f() shouldEqual 3
    f() shouldEqual 3
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
    thrown.getMessage shouldEqual "Molecule a/S cannot be used as input since it was already used in JoinDef{a/S => ...}"
  }

  it should "throw exception when join pattern attempts to redefine a non-blocking molecule" in {
    val thrown = intercept[Exception] {
      val a = ja[Unit]("x")
      val b = ja[Unit]("y")
      join( &{ case a(_) + b(_) => () })
      join( &{ case a(_) => () })
    }
    thrown.getMessage shouldEqual "Molecule x cannot be used as input since it was already used in JoinDef{x + y => ...}"
  }

  it should "throw exception when trying to inject a blocking molecule that has no join" in {
    val thrown = intercept[Exception] {
      val a = ja[Unit]("x")
      a()
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
    Thread.sleep(100) // give it some time to start processes
    f() shouldEqual 1
  }

  it should "implement the non-blocking single-access counter" in {
    val c = ja[Int]("counter")
    val d = ja[Unit]("decrement")
    val g = js[Unit,Int]("getValue")
    join(
      &{ case c(n) + d(_) => c(n-1) },
      &{ case c(n) + g(_,r) => c(n) + r(n) }
    )
    c(2)
    d()
    d()
    Thread.sleep(100) // give it some time to start processes
    g() shouldEqual 0

  }
}
