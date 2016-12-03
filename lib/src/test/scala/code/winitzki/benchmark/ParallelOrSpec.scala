package code.winitzki.benchmark

import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Pool
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.{Millis, Span}
import org.scalatest.{FlatSpec, Matchers}

import scala.annotation.tailrec

class ParallelOrSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(500, Millis)

  @tailrec
  final def neverReturn[T]: T = { Thread.sleep(1000000); neverReturn[T] }

  lazy val never = neverReturn[Boolean]

  def or(b1: => Boolean, b2: => Boolean, tp: Pool): B[Unit, Boolean] = {
    val res = new B[Unit, Boolean]("res")
    val res1 = new M[Boolean]("res1")
    val res2 = new M[Boolean]("res2")
    val res1_false = new B[Unit, Boolean]("res1_false")
    val res2_false = new B[Unit, Boolean]("res2_false")

    join(tp, tp) (
      runSimple { case res(_, res_reply) + res1(b) => if (b) res_reply(b) else res_reply(res1_false()) },
      runSimple { case res1_false(_, res1f_reply) + res2(b) => res1f_reply(b) },
      runSimple { case res(_, res_reply) + res2(b) => if (b) res_reply(b) else res_reply(res2_false()) },
      runSimple { case res2_false(_, res2f_reply) + res1(b) => res2f_reply(b) }
    )

    res1(b1) + res2(b2)
    res
  }

  /* This requires lazy molecule values, which is currently not implemented.

  it should "implement parallel OR operation correctly" in {
    or(true, true)() shouldEqual true
    or(true, false)() shouldEqual true
    or(false, true)() shouldEqual true
    or(false, false)() shouldEqual false
    or(true, never)() shouldEqual true
    or(never, true)() shouldEqual true
  }
  */

}
