package code.winitzki.benchmark

import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.{Millis, Span}
import org.scalatest.{FlatSpec, Matchers}


class JiansenFairnessSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(2000, Millis)

  // fairness over reactions:
  // We have n molecules A:JA[Unit], which can all interact with a single molecule C:JA[(Int,Array[Int])].
  // We first emit all A's and then a single C.
  // Each molecule A_i will increment C's counter at index i upon reaction.
  // We repeat this for N iterations, then we read the array and check that its values are distributed more or less randomly.

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
  // Emit n molecules A[Int] that can all interact with C[Int]. Each time they interact, their counter is incremented.
  // Then emit a single C molecule, which will react until its counter goes to 0.
  // At this point, gather all results from A[Int] into an array and return that array.

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
