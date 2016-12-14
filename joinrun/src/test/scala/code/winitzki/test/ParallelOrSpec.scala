package code.winitzki.test

import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Macros._
import code.winitzki.jc.{FixedPool, Pool}
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.{Millis, Span}
import org.scalatest.{FlatSpec, Matchers}

import scala.annotation.tailrec
import scala.reflect.ClassTag

class ParallelOrSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(1500, Millis)
/*
  def parallelOr[T](b1: B[Unit, T], b2: B[Unit, T], tp: Pool): B[Unit, T] = {
    val res = b[Unit, T]
    val res1 = m[T]
    val res2 = m[T]
    val res1_false = b[Unit, T]
    val res2_false = b[Unit, T]

    join(tp, tp) (
      & { case res(_, res_reply) + res1(b) => if (b) res_reply(b) else res_reply(res1_false()) },
      & { case res1_false(_, res1f_reply) + res2(b) => res1f_reply(b) },
      & { case res(_, res_reply) + res2(b) => if (b) res_reply(b) else res_reply(res2_false()) },
      & { case res2_false(_, res2f_reply) + res1(b) => res2f_reply(b) }
    )

    res1(b1) + res2(b2)
    res
  }
*/

  /** Given two blocking molecules b1 and b2, construct a new blocking molecule injector that returns
    * the result of whichever of b1 and b2 unblocks first.
    *
    * @param b1 First blocking molecule injector.
    * @param b2 Second blocking molecule injector.
    * @param tp Thread pool on which to run this.
    * @tparam T Type of the return value.
    * @return New blocking molecule injector that will return the desired result.
    */
  def firstResult[T : ClassTag](b1: B[Unit, T], b2: B[Unit, T], tp: Pool): B[Unit, T] = {
    val get = b[Unit, T]
    val res = b[Unit, T]
    val res1 = m[Unit]
    val res2 = m[Unit]
    val done = m[T]

    join(tp, tp) (
      & { case res1(_) => val x = b1(); done(x) }, // IntelliJ 2016.3 insists on `b1(())` here, but scalac is fine with `b1()`.
      & { case res2(_) => val x = b2(); done(x) }
    )

    join(tp, tp)( & { case get(_, r) + done(x) => r(x) })

    join(tp, tp)( & { case res(_, r) =>  res1() + res2(); val x = get(); r(x) })

    res
  }

  @tailrec
  final def neverReturn[T]: T = { Thread.sleep(1000000); neverReturn[T] }

  it should "implement the First Result operation correctly" in {

    val never = b[Unit, String]
    val fast = b[Unit, String]
    val slow = b[Unit, String]

    val tp = new FixedPool(30)

    join(tp, tp)(
      & {case never(_, r) => r(neverReturn[String])},
      & {case fast(_, r) => Thread.sleep(10); r("fast")},
      & {case slow(_, r) => Thread.sleep(100); r("slow")}
    )

    implicit val stringClassTag = implicitly[ClassTag[String]]

    firstResult(fast, fast, tp)(stringClassTag)() shouldEqual "fast"
    firstResult(fast, slow, tp)(stringClassTag)() shouldEqual "fast"
    firstResult(slow, fast, tp)(stringClassTag)() shouldEqual "fast"
    firstResult(slow, slow, tp)(stringClassTag)() shouldEqual "slow"
    firstResult(fast, never, tp)(stringClassTag)() shouldEqual "fast"
    firstResult(never, slow, tp)(stringClassTag)() shouldEqual "slow"

    tp.shutdownNow()
  }

}
