package code.winitzki.test

import code.winitzki.jc.FixedPool
import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Macros._
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.{Millis, Span}
import org.scalatest.{FlatSpec, Matchers}

class FairnessSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(2000, Millis)

  behavior of "reaction site"

  // fairness over reactions:
  // We have n molecules A:M[Unit], which can all interact with a single molecule C:M[(Int,Array[Int])].
  // We first emit all A's and then a single C.
  // Each molecule A_i will increment C's counter at index i upon reaction.
  // We repeat this for N iterations, then we read the array and check that its values are distributed more or less randomly.

  it should "implement fairness across reactions" in {

    val reactions = 4
    val N = 1000

    val c = new M[(Int, Array[Int])]("c")
    val done = new M[Array[Int]]("done")
    val getC = new B[Unit, Array[Int]]("getC")
    val a0 = m[Unit]
    val a1 = m[Unit]
    val a2 = m[Unit]
    val a3 = m[Unit]
    //n = 4

    val tp = new FixedPool(4)
    val tp1 = new FixedPool(1)

    site(tp, tp1)(
      go { case getC(_, r) + done(arr) => r(arr) },
      go { case a0(_) + c((n,arr)) => if (n > 0) { arr(0) += 1; c((n-1,arr)) + a0() } else done(arr) },
      go { case a1(_) + c((n,arr)) => if (n > 0) { arr(1) += 1; c((n-1,arr)) + a1() } else done(arr) },
      go { case a2(_) + c((n,arr)) => if (n > 0) { arr(2) += 1; c((n-1,arr)) + a2() } else done(arr) },
      go { case a3(_) + c((n,arr)) => if (n > 0) { arr(3) += 1; c((n-1,arr)) + a3() } else done(arr) }
    )

    a0() + a1() + a2() + a3()
    c((N, Array.fill[Int](reactions)(0)))

    val result = getC()
//    println(result.mkString(", "))

    tp.shutdownNow()
    tp1.shutdownNow()

    result.min should be > (0.75*N/reactions).toInt
    result.max should be < (1.25*N/reactions).toInt

  }

  // fairness across molecules:
  // Emit n molecules A[Int] that can all interact with C[Int]. Each time they interact, their counter is incremented.
  // Then emit a single C molecule, which will react until its counter goes to 0.
  // At this point, gather all results from A[Int] into an array and return that array.

  it should "fail to implement fairness across molecules" in {

    val counters = 10

    val cycles = 1000

    val c = m[Int]
    val done = m[List[Int]]
    val getC = b[Unit, List[Int]]
    val gather = m[List[Int]]
    val a = m[Int]

    val tp = new FixedPool(8)

    site(tp, tp)(
      go { case done(arr) + getC(_, r) => r(arr) },
      go { case c(n) + a(i) if n>0 => a(i+1) + c(n-1) },
      go { case c(0) + a(i) => a(i) + gather(List()) },
      go { case gather(arr) + a(i) =>
        val newArr = i :: arr
        if (newArr.size < counters) gather(newArr) else done(newArr) }
    )

    (1 to counters).foreach(_ => a(0))
    Thread.sleep(100)
    c(cycles)

    val result = getC()
    println(result.mkString(", "))

    tp.shutdownNow()

    result.min should be < (cycles/counters/2)
    result.max should be > (cycles/counters*2)
  }

  behavior of "multiple emission"

  /** Emit an equal number of a,b,c molecules. One reaction consumes a+b and the other consumes b+c.
    * Verify that both reactions proceed with probability roughly 1/2.
    */
  it should "schedule reactions fairly after multiple emission" in {
    val a = m[Unit]
    val bb = m[Unit]
    val c = m[Unit]
    val d = m[Unit]
    val e = m[Unit]
    val f = m[(Int,Int,Int)]
    val g = b[Unit, (Int,Int)]

    val tp = new FixedPool(8)

    site(tp, tp)(
      go { case a(_) + bb(_) => d() },
      go { case bb(_) + c(_) => e() },
      go { case d(_) + f((x,y,t)) => f((x+1,y,t-1)) },
      go { case e(_) + f((x,y,t)) => f((x,y+1,t-1)) },
      go { case g(_,r) + f((x,y,0)) => r((x,y)) }
    )

    val n = 1000

    f((0,0, n))

    (1 to n).foreach{ _ => a()+bb()+c() }

    val (ab, bc) = g()
    ab + bc shouldEqual n
    val discrepancy = math.abs(ab - bc + 0.0) / n
    discrepancy should be < 0.15

    tp.shutdownNow()
  }

  // interestingly, this test fails to complete in 500ms on Travis CI with Scala 2.10, but succeeds with 2.11
  it should "fail to schedule reactions fairly after multiple emission into separate RSs" in {

    val tp = new FixedPool(8)

    def makeRS(d1: M[Unit], d2: M[Unit]): (M[Unit],M[Unit],M[Unit]) = {
      val a = m[Unit]
      val b = m[Unit]
      val c = m[Unit]

      site(tp, tp)(
        go { case a(_) + b(_) => d1() },
        go { case b(_) + c(_) => d2() }
      )
      (a,b,c)
    }

    val d = m[Unit]
    val e = m[Unit]
    val f = m[(Int,Int,Int)]
    val g = b[Unit, (Int,Int)]

    site(tp, tp)(
      go { case d(_) + f((x,y,t)) => f((x+1,y,t-1)) },
      go { case e(_) + f((x,y,t)) => f((x,y+1,t-1)) },
      go { case g(_,r) + f((x,y,0)) => r((x,y)) }
    )

    val n = 400

    f((0,0, n))

    (1 to n).foreach{ _ =>
      val (a,b,c) = makeRS(d,e)
      a()+b()+c() // at the moment, this is equivalent to a(); b(); c.
      // this test will need to be changed when true multiple emission is implemented.
    }

    val (ab, bc) = g()
    ab + bc shouldEqual n
    val discrepancy = math.abs(ab - bc + 0.0) / n
    discrepancy should be > 0.4

    tp.shutdownNow()
  }

}
