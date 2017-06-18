package io.chymyst.test

import io.chymyst.jc._
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

class EventHooksSpec extends FlatSpec with Matchers {

  behavior of "whenEmitted"

  it should "resolve future when some molecule is emitted" in {
    val a = m[Int]
    val c = m[Int]
    withPool(FixedPool(2)) { tp ⇒
      site(tp)(
        go { case a(x) + c(_) ⇒ c(x) }
      )
      (1 to 10).foreach(a)
      c(0)
      val fut = c.whenEmitted
      val res = Await.result(fut, 2.seconds)
      res shouldEqual 1
    }.get
  }

  it should "resolve future when some blocking molecule is emitted" in {
    val a = m[Int]
    val c = b[Int, Int]
    withPool(FixedPool(2)) { tp ⇒
      site(tp)(
        go { case a(x) ⇒ c(x); () }
        , go { case c(_, r) ⇒ r(0) }
      )
      val fut = c.whenEmitted
      a(123)

      val res = Await.result(fut, 2.seconds)
      res shouldEqual 123
    }.get
  }

  behavior of "whenScheduled"

  it should "resolve when some reaction is scheduled" in {
    val res = (1 to 20).map { _ =>
      val a = m[Int]
      val c = m[Int]
      withPool(FixedPool(2)) { tp ⇒
        site(tp)(
          go { case a(x) + c(_) ⇒ c(x) }
        )
        val fut = a.whenScheduled
        c(0)
        a(1)
        Try(Await.result(fut, 2.seconds)) match {
          case Failure(exception) ⇒ "none"
          case Success(value) ⇒ value
        }
      }.get
    }.toSet
    println(res)
    res should contain("c")
  }

  it should "sometimes fail to resolve when reaction scheduler finishes too early" in {
    val res = (1 to 20).map { _ =>
      val a = m[Int]
      val c = m[Int]
      withPool(FixedPool(2)) { tp ⇒
        site(tp)(
          go { case a(x) + c(_) ⇒ c(x) }
        )
        val fut = c.whenScheduled
        c(0)
        a(1)
        Try(Await.result(fut, 2.seconds)) match {
          case Failure(exception) ⇒ "none"
          case Success(value) ⇒ value
        }
      }.get
    }.toSet
    println(res)
    res should contain("c")
  }

  it should "resolve when another reaction is scheduled" in {
    val res = (1 to 20).map { _ =>
      val a = m[Int]
      val c = m[Int]
      withPool(FixedPool(2)) { tp ⇒
        site(tp)(
          go { case a(x) + c(_) ⇒ c(x) }
        )
        val fut = c.whenScheduled
        a(1)
        c(0)
        Try(Await.result(fut, 2.seconds)) match {
          case Failure(_) ⇒ "none"
          case Success(v) ⇒ v
        }
      }.get
    }.toSet
    println(res)
    res should contain("a")
  }

  it should "fail to resolve when no reaction can be scheduled" in {
    val a = m[Int]
    val c = m[Int]
    withPool(FixedPool(2)) { tp ⇒
      site(tp)(
        go { case a(x) + c(_) ⇒ c(x) }
      )
      val fut = a.whenScheduled
      a(1)
      the[Exception] thrownBy Await.result(fut, 2.seconds) should have message "a.whenScheduled() failed because no reaction could be scheduled (this is not an error)"
    }.get

  }

  it should "be an automatic failure when used on reaction threads" in {
    val a = m[Int]
    val c = b[Unit, Future[String]]
    withPool(FixedPool(2)) { tp ⇒
      site(tp)(
        go { case a(x) + c(_, r) ⇒ r(a.whenScheduled) }
      )
      a(1)
      val fut = c()
      fut.isCompleted shouldEqual  true
      the[Exception] thrownBy Await.result(fut, 2.seconds) should have message "whenScheduled() is disallowed on reaction threads (molecule: a)"
    }.get
  }

  behavior of "whenConsumed"

  it should "resolve when a molecule is consumed by reaction" in {
    val a = m[Unit]
    withPool(FixedPool(2)) { tp ⇒
      site(tp)(
        go { case a(x) ⇒ }
      )
      val fut = a.emitUntilConsumed()
      Await.result(fut, 2.seconds) shouldEqual (())
    }.get
  }

  it should "time out when another copy of molecule is consumed" in {
    val a = m[Int]
    val f = b[Unit, Unit]
    withPool(FixedPool(2)) { tp ⇒
      site(tp)(
        go { case a(x) + f(_, r) ⇒ r() }
      )
      a.isPipelined shouldEqual true
      val fut0 = a.emitUntilConsumed(10)
      val fut = a.emitUntilConsumed(123)
      f()
      fut0.isCompleted shouldEqual true
      the[Exception] thrownBy Await.result(fut, 500.millis) should have message "Futures timed out after [500 milliseconds]"
      Await.result(fut0, 500.millis) shouldEqual 10
    }.get

  }

  it should "be an automatic failure when used on reaction threads" in {
    val a = m[Int]
    val c = b[Unit, Future[Int]]
    withPool(FixedPool(2)) { tp ⇒
      site(tp)(
        go { case a(x) + c(_, r) ⇒ r(a.emitUntilConsumed(123)) }
      )
      a(1)
      val fut = c()
      fut.isCompleted shouldEqual  true
      the[Exception] thrownBy Await.result(fut, 2.seconds) should have message "emitUntilConsumed() is disallowed on reaction threads (molecule: a)"
    }.get
  }

  it should "signal error on static molecules" in {
    val a = m[Unit]
    val c = m[Unit]
    site(
      go { case _ ⇒ a() }
      , go { case a(_) + c(_) ⇒ a() }
    )
    a.isStatic shouldEqual true
    c.isStatic shouldEqual false
    the[ExceptionEmittingStaticMol] thrownBy a.emitUntilConsumed() should have message "Error: static molecule a(()) cannot be emitted non-statically"
  }

  behavior of "user code utilizing test hooks"

  def makeCounter(initValue: Int, tp: Pool): (M[Unit], B[Unit, Int]) = {
    val c = m[Int]
    val d = m[Unit]
    val f = b[Unit, Int]

    site(tp)(
      go { case c(x) + f(_, r) ⇒ c(x) + r(x) },
      go { case c(x) + d(_) ⇒ c(x - 1) },
      go { case _ ⇒ c(initValue) }
    )

    (d, f)
  }

  it should "verify the operation of the concurrent counter" in {
    val (d, f) = makeCounter(10, FixedPool(2))

    val x = f() // current value
    val fut = d.emitUntilConsumed()
    // give a timeout just to be safe; actually, this will be quick
    Await.result(fut, 5.seconds)
    f() shouldEqual x - 1
  }

}
