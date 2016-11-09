package code.winitzki.benchmark

import code.winitzki.jc.{AsyName, Join, SynName, and}
import code.winitzki.jc.JoinRun._
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.{Millis, Span}
import org.scalatest.{FlatSpec, Matchers}

import scala.annotation.tailrec

class VariousExamples1Spec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(500, Millis)

  @tailrec
  final def neverReturn[T]: T = { Thread.sleep(1000000); neverReturn[T] }

  lazy val never = neverReturn[Boolean]

  def or(b1: => Boolean, b2: => Boolean): JS[Unit, Boolean] = {
    val res = js[Unit, Boolean]
    val res1 = ja[Boolean]
    val res2 = ja[Boolean]
    val res1_false = js[Unit, Boolean]
    val res2_false = js[Unit, Boolean]

    join (
      &{ case res(_, res_reply) + res1(b) => if (b) res_reply(b) else res_reply(res1_false()) },
      &{ case res1_false(_, res1f_reply) + res2(b) => res1f_reply(b) },
      &{ case res(_, res_reply) + res2(b) => if (b) res_reply(b) else res_reply(res2_false()) },
      &{ case res2_false(_, res2f_reply) + res1(b) => res2f_reply(b) }
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

  // fairness over reactions:
  // We have n molecules A:JA[Unit], which can all interact with a single molecule C:JA[(Int,Array[Int])].
  // We first inject all A's and then a single C.
  // Each molecule A_i will increment C's counter at index i upon reaction.
  // We repeat this for N iterations, then we read the array and check that its values are distributed more or less randomly.

  it should "implement fairness across reactions" in {

    val reactions = 4
    val N = 1000

    val c = ja[(Int, Array[Int])]("c")
    val done = ja[Array[Int]]("done")
    val getC = js[Unit, Array[Int]]("getC")
    val a0 = ja[Unit]("a0")
    val a1 = ja[Unit]("a1")
    val a2 = ja[Unit]("a2")
    val a3 = ja[Unit]("a3")
    //n = 4

    join(
      &{ case getC(_, r) + done(arr) => r(arr) },
      &{ case a0(_) + c((n,arr)) => if (n > 0) { arr(0) += 1; c((n-1,arr)) + a0() } else done(arr) },
      &{ case a1(_) + c((n,arr)) => if (n > 0) { arr(1) += 1; c((n-1,arr)) + a1() } else done(arr) },
      &{ case a2(_) + c((n,arr)) => if (n > 0) { arr(2) += 1; c((n-1,arr)) + a2() } else done(arr) },
      &{ case a3(_) + c((n,arr)) => if (n > 0) { arr(3) += 1; c((n-1,arr)) + a3() } else done(arr) }
    )

    a0() + a1() + a2() + a3()
    c((N, Array.fill[Int](reactions)(0)))

    val result = getC()
//    println(result.mkString(", "))

    result.min should be > (0.75*N/reactions).toInt
    result.max should be < (1.25*N/reactions).toInt

  }

  it should "fail to implement fairness across reactions in Jiansen's Join" in {

    val reactions = 4
    val N = 100 // with 1000 we get a stack overflow

    object j3 extends Join {
      object c extends AsyName[(Int, Array[Int])]
      object done extends AsyName[Array[Int]]
      object getC extends SynName[Unit, Array[Int]]
      object a0 extends AsyName[Unit]
      object a1 extends AsyName[Unit]
      object a2 extends AsyName[Unit]
      object a3 extends AsyName[Unit]

      join {
        case getC(_) and done(arr) => getC.reply(arr)
        case a0(_) and c((n, arr)) => if (n > 0) { arr(0) += 1; c((n-1,arr)); a0() } else done(arr)
        case a1(_) and c((n, arr)) => if (n > 0) { arr(1) += 1; c((n-1,arr)); a1() } else done(arr)
        case a2(_) and c((n, arr)) => if (n > 0) { arr(2) += 1; c((n-1,arr)); a2() } else done(arr)
        case a3(_) and c((n, arr)) => if (n > 0) { arr(3) += 1; c((n-1,arr)); a3() } else done(arr)
      }

    }
    j3.a0(); j3.a1(); j3.a2(); j3.a3()
    j3.c((N, Array.fill[Int](reactions)(0)))

    val result = j3.getC()

//    println(result.mkString(", "))

    result.min should be (0)
    result.max should be (N)

  }

  // fairness across molecules:
  // Inject n molecules A[Int] that can all interact with C[Int]. Each time they interact, their counter is incremented.
  // Then inject a single C molecule, which will react until its counter goes to 0.
  // At this point, gather all results from A[Int] into an array and return that array.

  it should "fail to implement fairness across molecules" in {

    val counters = 10

    val cycles = 1000

    val c = ja[Int]("c")
    val done = ja[List[Int]]("done")
    val getC = js[Unit, List[Int]]("getC")
    val gather = ja[List[Int]]("gather")
    val a = ja[Int]("a")

    join(
      &{ case done(arr) + getC(_, r) => r(arr) },
      &{ case c(n) + a(i) => if (n>0) { a(i+1) + c(n-1) } else a(i) + gather(List()) },
      &{ case gather(arr) + a(i) =>
        val newArr = i :: arr
        if (newArr.size < counters) gather(newArr) else done(newArr) }
    )

    (1 to counters).foreach(_ => a(0))
    Thread.sleep(200)
    c(cycles)

    val result = getC()
//    println(result.mkString(", "))

    result.min should be < (cycles/counters/2)
    result.max should be > (cycles/counters*3)
  }

  it should "fail to implement fairness across molecules in Jiansen's Join" in {

    val counters = 10

    val cycles = 40 // again, stack overflow with 1000 counters

    object j4 extends Join {
      object c extends AsyName[Int]
      object done extends AsyName[List[Int]]
      object getC extends SynName[Unit, List[Int]]
      object gather extends AsyName[List[Int]]
      object a extends AsyName[Int]

      join {
        case getC(_) and done(arr) => getC.reply(arr)
        case c(n) and a(m) => if (n > 0) { c(n-1); a(m+1) } else { a(m); gather(List()) }
        case gather(arr) and a(i) =>
          val newArr = i :: arr
          if (newArr.size < counters) gather(newArr) else done(newArr)
      }
    }

    (1 to counters).foreach(_ => j4.a(0))
    Thread.sleep(200)
    j4.c(cycles)

    val result = j4.getC()

//    println(result.mkString(", "))

    result.max should be (0)

  }

}
