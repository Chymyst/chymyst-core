package sample

import org.scalatest.{Matchers, FlatSpec}
import org.scalatest.concurrent.Waiters.Waiter
import JoinDef._
import JoinDef.{run => jr}

class JoinDefSpec extends FlatSpec with Matchers {
  it should "start a simple reaction with one input" in {

    val waiter = new Waiter

    val a = ja[Unit]
    joindef(jr{ case a(_) => waiter.dismiss() })
    a()
    waiter.await()
  }

  it should "start a simple reaction chain" in {

    val waiter = new Waiter

    val a = ja[Unit]("a")
    val b = ja[Unit]("b")
    joindef(jr{ case a(_) => b() }, jr{ case b(_) => waiter.dismiss() })
    a.setLogLevel(3)
    a()
    waiter.await()
  }

  it should "start a simple reaction chain with two inputs with values" in {

    val waiter = new Waiter

    val a = ja[Int]
    val b = ja[Int]
    val c = ja[Int]
    joindef(jr{ case a(x) & b(y) => c(x+y) }, jr{ case c(z) => waiter { z shouldEqual 3 }; waiter.dismiss() })
    a(1)
    b(2)
    waiter.await()
  }

  it should "block for a synchronous molecule" in {

    val a = ja[Unit]
    val f = js[Unit,Int]
    joindef(jr{ case a(_) & f(_, r) => r(3) })
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
      joindef(jr { case a(_) & a(_) => () })
      a()
    }
    thrown.getMessage shouldEqual "Nonlinear pattern: a used twice"

  }

  it should "throw exception when join pattern is nonlinear, with blocking molecule" in {
    val thrown = intercept[Exception] {
      val a = js[Unit,Unit]("a")
      joindef(&{ case a(_,r) & a(_,s) => () })
      a()
    }
    thrown.getMessage shouldEqual "Nonlinear pattern: a/S used twice"
  }

  it should "throw exception when join pattern attempts to redefine a blocking molecule" in {
    val thrown = intercept[Exception] {
      val a = js[Unit,Unit]("a")
      joindef(& { case a(_,_) => () })
      joindef(& { case a(_,_) => () })
    }
    thrown.getMessage shouldEqual "Molecule a/S cannot be used as input since it was already used in JoinDef{a/S => ...}"
  }

  it should "throw exception when join pattern attempts to redefine a non-blocking molecule" in {
    val thrown = intercept[Exception] {
      val a = ja[Unit]("x")
      val b = ja[Unit]("y")
      joindef(& { case a(_) + b(_) => () })
      joindef(& { case a(_) => () })
    }
    thrown.getMessage shouldEqual "Molecule x cannot be used as input since it was already used in JoinDef{x + y => ...}"
  }
}
