package code.winitzki.benchmark

import code.winitzki.jc.JoinRun._
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.{Millis, Span}
import org.scalatest.{FlatSpec, Matchers}

class FairnessSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(500, Millis)

  behavior of "join definition"

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
    println(result.mkString(", "))

    result.min should be < (cycles/counters/2)
    result.max should be > (cycles/counters*3)
  }

  behavior of "multiple injection"

  it should "schedule reactions fairly after multiple injection" in {
    val a = ja[Unit]("a")
    val b = ja[Unit]("b")
    val c = ja[Unit]("c")
    val d = ja[Unit]("d")
    val e = ja[Unit]("e")
    val f = ja[(Int,Int,Int)]("f")
    val g = js[Unit, (Int,Int)]("g")

    join(
      &{ case a(_) + b(_) => d() },
      &{ case b(_) + c(_) => e() },
      &{ case d(_) + f((x,y,t)) => f((x+1,y,t-1)) },
      &{ case e(_) + f((x,y,t)) => f((x,y+1,t-1)) },
      &{ case g(_,r) + f((x,y,0)) => r((x,y)) }
    )

    val n = 1000

    f((0,0, n))

    (1 to n).foreach{ _ => a()+b()+c() }

    val (ab, bc) = g()
    val discrepancy = math.abs(ab-bc + 0.0)/(ab+bc)
    discrepancy should be < 0.05

  }


}
