package code.winitzki.benchmark

import code.winitzki.jc.JoinRun._
import org.scalatest.concurrent.TimeLimitedTests
import org.scalatest.time.{Millis, Span}
import org.scalatest.{FlatSpec, Matchers}

class FairnessSpec extends FlatSpec with Matchers with TimeLimitedTests {

  val timeLimit = Span(500, Millis)

  behavior of "join definition"

  // fairness over reactions:
  // We have n molecules A:M[Unit], which can all interact with a single molecule C:M[(Int,Array[Int])].
  // We first inject all A's and then a single C.
  // Each molecule A_i will increment C's counter at index i upon reaction.
  // We repeat this for N iterations, then we read the array and check that its values are distributed more or less randomly.

  it should "implement fairness across reactions" in {

    val reactions = 4
    val N = 1000

    val c = new M[(Int, Array[Int])]("c")
    val done = new M[Array[Int]]("done")
    val getC = new B[Unit, Array[Int]]("getC")
    val a0 = new M[Unit]("a0")
    val a1 = new M[Unit]("a1")
    val a2 = new M[Unit]("a2")
    val a3 = new M[Unit]("a3")
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

    val c = new M[Int]("c")
    val done = new M[List[Int]]("done")
    val getC = new B[Unit, List[Int]]("getC")
    val gather = new M[List[Int]]("gather")
    val a = new M[Int]("a")

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

  /** Inject an equal number of a,b,c molecules. One reaction consumes a+b and the other consumes b+c.
    * Verify that both reactions proceed with probability roughly 1/2.
    */
  it should "schedule reactions fairly after multiple injection" in {
    val a = new M[Unit]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")
    val d = new M[Unit]("d")
    val e = new M[Unit]("e")
    val f = new M[(Int,Int,Int)]("f")
    val g = new B[Unit, (Int,Int)]("g")

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
    ab + bc shouldEqual n
    val discrepancy = math.abs(ab - bc + 0.0) / n
    discrepancy should be < 0.1
  }

  // interestingly, this test fails to complete in 500ms on Travis CI with Scala 2.10, but succeeds with 2.11
  it should "fail to schedule reactions fairly after multiple injection into separate JDs" in {

    def makeJD(d1: M[Unit], d2: M[Unit]): (M[Unit],M[Unit],M[Unit]) = {
      val a = new M[Unit]("a")
      val b = new M[Unit]("b")
      val c = new M[Unit]("c")
      join(
        &{ case a(_) + b(_) => d1() },
        &{ case b(_) + c(_) => d2() }
      )
      (a,b,c)
    }

    val d = new M[Unit]("d")
    val e = new M[Unit]("e")
    val f = new M[(Int,Int,Int)]("f")
    val g = new B[Unit, (Int,Int)]("g")

    join(
      &{ case d(_) + f((x,y,t)) => f((x+1,y,t-1)) },
      &{ case e(_) + f((x,y,t)) => f((x,y+1,t-1)) },
      &{ case g(_,r) + f((x,y,0)) => r((x,y)) }
    )

    val n = 200

    f((0,0, n))

    (1 to n).foreach{ _ =>
      val (a,b,c) = makeJD(d,e)
      a()+b()+c() // at the moment, this is equivalent to a(); b(); c.
      // this test will need to be changed when true multiple injection is implemented.
    }

    val (ab, bc) = g()
    ab + bc shouldEqual n
    val discrepancy = math.abs(ab - bc + 0.0) / n
    discrepancy should be > 0.5
  }

}
