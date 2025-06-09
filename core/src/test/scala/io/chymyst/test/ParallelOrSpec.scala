package io.chymyst.test

import io.chymyst.jc._

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.language.postfixOps

class ParallelOrSpec extends LogSpec {

  behavior of "test"

  /** Given two blocking molecules f and g returning Boolean, construct a new blocking molecule `parallelOr`
    * that returns the Boolean Or value of whichever of f and g unblocks first.
    * If one of `f` and `g` returns `true` then `parallelOr` also unblocks and returns `true`.
    * If both `f` and `g` return `false` then `parallelOr` also returns `false`.
    * Otherwise (if both `f` and `g` remain blocked or one of them returns `false` while the other remains blocked),
    * `parallelOr` will continue to be blocked.
    *
    * @param f  First blocking molecule emitter.
    * @param g  Second blocking molecule emitter.
    * @param tp Thread pool on which to run this.
    * @return New blocking molecule emitter that will return the desired result or block.
    */
  def parallelOr(f: B[Unit, Boolean], g: B[Unit, Boolean], tp: Pool): B[Unit, Boolean] = {
    val c = m[Unit]
    val d = m[Unit]
    val done = m[Boolean]
    val result = m[Int]
    val finalResult = m[Boolean]

    val parallelOr = b[Unit, Boolean]

    site(tp)(
      go { case parallelOr(_, r) + finalResult(x) => r(x) }
    )

    site(tp)(
      go { case result(_) + done(true) => finalResult(true) },
      go { case result(1) + done(false) => finalResult(false) },
      go { case result(0) + done(false) => result(1) }
    )

    site(tp)(
      go { case c(_) => val x = f(); done(x) },
      go { case d(_) => val y = g(); done(y) }
    )

    c() + d() + result(0)

    parallelOr
  }

  it should "implement the Parallel Or operation correctly" in {

    val never = b[Unit, Boolean]
    val fastTrue = b[Unit, Boolean]
    val slowTrue = b[Unit, Boolean]
    val fastFalse = b[Unit, Boolean]
    val slowFalse = b[Unit, Boolean]

    val tp = FixedPool(30)

    site(tp)(
      go { case never(_, r) => r(neverReturn[Boolean]) },
      go { case fastTrue(_, r) => Thread.sleep(10); r(true) },
      go { case slowTrue(_, r) => Thread.sleep(200); r(true) },
      go { case fastFalse(_, r) => Thread.sleep(10); r(false) },
      go { case slowFalse(_, r) => Thread.sleep(200); r(false) }
    )

    parallelOr(fastTrue, fastFalse, tp)() shouldEqual true
    parallelOr(fastTrue, slowFalse, tp)() shouldEqual true
    parallelOr(slowTrue, fastFalse, tp)() shouldEqual true
    parallelOr(slowTrue, slowFalse, tp)() shouldEqual true
    parallelOr(fastTrue, never, tp)() shouldEqual true
    parallelOr(never, slowTrue, tp)() shouldEqual true

    parallelOr(slowFalse, fastFalse, tp)() shouldEqual false
    parallelOr(slowFalse, fastFalse, tp)() shouldEqual false
    parallelOr(fastFalse, slowTrue, tp)() shouldEqual true

    parallelOr(never, fastFalse, tp).timeout()(200 millis) shouldEqual None
    parallelOr(never, slowFalse, tp).timeout()(200 millis) shouldEqual None

    tp.shutdownNow()
  }

  /** Given two blocking molecules b1 and b2, construct a new blocking molecule emitter that returns
    * the result of whichever of b1 and b2 unblocks first.
    *
    * @param b1 First blocking molecule emitter.
    * @param b2 Second blocking molecule emitter.
    * @param tp Thread pool on which to run this.
    * @tparam T Type of the return value.
    * @return New blocking molecule emitter that will return the desired result.
    */
  def firstResult[T](b1: B[Unit, T], b2: B[Unit, T], tp: Pool): B[Unit, T] = {
    val get = b[Unit, T]
    val res = b[Unit, T]
    val res1 = m[Unit]
    val res2 = m[Unit]
    val done = m[T]

    site(tp)(
      go { case res1(_) => val x = b1(); done(x) },
      go { case res2(_) => val x = b2(); done(x) }
    )

    site(tp)(go { case get(_, r) + done(x) => r(x) })

    site(tp)(go { case res(_, r) => res1() + res2(); val x = get(); r(x) })

    res
  }

  @tailrec
  final def neverReturn[T]: T = {
    Thread.sleep(1000000)
    neverReturn[T]
  }

  it should "implement the First Result operation correctly" in {

    val never = b[Unit, String]
    val fast = b[Unit, String]
    val slow = b[Unit, String]

    val tp = FixedPool(30)

    site(tp)(
      go { case never(_, r) => r(neverReturn[String]) },
      go { case fast(_, r) => Thread.sleep(10); r("fast") },
      go { case slow(_, r) => Thread.sleep(200); r("slow") }
    )

    firstResult(fast, fast, tp)() shouldEqual "fast"
    firstResult(fast, slow, tp)() shouldEqual "fast"
    firstResult(slow, fast, tp)() shouldEqual "fast"
    firstResult(slow, slow, tp)() shouldEqual "slow"
    firstResult(fast, never, tp)() shouldEqual "fast"
    firstResult(never, slow, tp)() shouldEqual "slow"

    tp.shutdownNow()
  }

}
