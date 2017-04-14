package io.chymyst.benchmark

import io.chymyst.jc._
import org.scalatest.{FlatSpec, Matchers}
import scala.concurrent.duration._

class MapReduceSpec extends FlatSpec with Matchers {

  def elapsed(initTime: Long): Long = System.currentTimeMillis() - initTime

  behavior of "map-reduce-like reactions"

  it should "perform a map/reduce-like computation" in {
    val count = 10000

    val initTime = System.currentTimeMillis()

    val res = m[List[Int]]
    val r = m[Int]
    val d = m[Int]
    val get = b[Unit, List[Int]]

    val tp = new FixedPool(4)
    site(tp)(
      go { case d(n) => r(n * 2) },
      go { case res(list) + r(s) => res(s :: list) },
      go { case get(_, reply) + res(list) if list.size == count => reply(list) } // expect warning: "non-variable type argument Int in type pattern List[Int] eliminated by erasure"
    )

    (1 to count).foreach(d(_))
    val expectedResult = (1 to count).map(_ * 2)
    res(Nil)

    get().toSet shouldEqual expectedResult.toSet

    tp.shutdownNow()
    println(s"map/reduce test with n=$count took ${elapsed(initTime)} ms")
  }

  it should "perform map-reduce as in tutorial, object C1" in {
    // declare the "map" and the "reduce" functions
    def f(x: Int): Int = x * x

    def reduceB(acc: Int, x: Int): Int = acc + x

    val initTime = System.currentTimeMillis()

    val arr = 1 to 100000

    // declare molecule types
    val carrier = m[Int]
    val interm = m[Int]
    val accum = m[(Int, Int)]
    val fetch = b[Unit, Int]

    val tp = new FixedPool(8)

    // declare the reaction for "map"
    site(tp)(
      go { case carrier(x) => val res = f(x); interm(res) }
    )

    // reactions for "reduce" must be together since they share "accum"
    site(tp)(
      go { case accum((n, b)) + interm(res) =>
        accum((n + 1, reduceB(b, res)))
      },
      go { case accum((n, b)) + fetch(_, reply) if n == arr.size => reply(b) }
    )

    // emit molecules
    accum((0, 0))
    arr.foreach(i => carrier(i))
    val result = fetch()
    result shouldEqual arr.map(f).reduce(reduceB) // 338350
    println(s"map-reduce as in tutorial object C1 with arr.size=${arr.size}: took ${elapsed(initTime)} ms")
    tp.shutdownNow()
  }

  it should "perform map-reduce as in tutorial, object C2" in {
    // declare the "map" and the "reduce" functions
    def f(x: Int): Int = x * x

    def reduceB(acc: Int, x: Int): Int = acc + x

    val initTime = System.currentTimeMillis()

    val arr = 1 to 100000

    // declare molecule types
    val carrier = m[Int]
    val interm = m[(Int, Int)]
    val fetch = b[Unit, Int]

    val tp = new FixedPool(8)

    // declare the reaction for "map"
    site(tp)(
      go { case carrier(x) => val res = f(x); interm((1, res)) }
    )

    // reactions for "reduce" must be together since they share "accum"
    site(tp)(
      go { case interm((n1, x1)) + interm((n2, x2)) ⇒
        interm((n1 + n2, reduceB(x1, x2)))
      },
      go { case interm((n, x)) + fetch(_, reply) if n == arr.size ⇒ reply(x) }
    )

    // emit molecules
    arr.foreach(i => carrier(i))
    val result = fetch()
    result shouldEqual arr.map(f).reduce(reduceB) // 338350
    println(s"map-reduce as in tutorial object C2 with arr.size=${arr.size}: took ${elapsed(initTime)} ms")
    tp.shutdownNow()
  }

  it should "compute the sum of numbers on molecules using nonlinear input pattern" in {
    val c = m[(Int, Int)]
    val done = m[Int]
    val f = b[Unit, Int]

    val count = 10000

    val tp = new FixedPool(cpuCores + 1)
    val tp1 = new FixedPool(1)
    val initTime = System.currentTimeMillis()

    site(tp, tp1)(
      go { case f(_, r) + done(x) => r(x) },
      go { case c((n, x)) + c((m, y)) =>
        val p = n + m
        val z = x + y
        if (p == count)
          done(z)
        else
          c((n + m, z))
      }
    )

    (1 to count).foreach(i => c((1, i * i)))
    f() shouldEqual (1 to count).map(i => i * i).sum

    tp.shutdownNow()
    tp1.shutdownNow()
    println(s"sum of $count numbers with nonlinear input patterns and 1-thread site pool took ${elapsed(initTime)} ms")
  }

  it should "compute the sum of numbers on molecules using nonlinear input pattern and cross-molecule conditionals" in {
    val c = m[(Int, Int)]
    val done = m[Int]
    val f = b[Unit, Int]

    val count = 10000

    val tp = new FixedPool(cpuCores + 1)
    val tp1 = new FixedPool(1)
    val initTime = System.currentTimeMillis()

    site(tp, tp1)(
      go { case f(_, r) + done(x) => r(x) },
      go { case c((n, x)) + c((m, y)) if x <= y =>
        val p = n + m
        val z = x + y
        if (p == count)
          done(z)
        else
          c((n + m, z))
      }
    )

    (1 to count).foreach(i => c((1, i * i)))
    f() shouldEqual (1 to count).map(i => i * i).sum

    tp.shutdownNow()
    tp1.shutdownNow()
    println(s"sum of $count numbers with nonlinear input patterns and cross-molecule conditionals and 1-thread site pool took ${elapsed(initTime)} ms")
  }

  it should "compute the sum of numbers on molecules using nonlinear input pattern and branching emitters" in {
    val a = m[Int]
    val c = m[(Int, Int)]
    val done = m[Int]
    val f = b[Unit, Int]

    val count = 100000

    val tp = new FixedPool(cpuCores + 1)
    val tp1 = new FixedPool(1)
    val initTime = System.currentTimeMillis()

    site(tp, tp1)(
      go {
        case a(x) if x <= count ⇒
          c((1, x * x))
          // When these IF conditions are restored, performance improves slightly.
          //          if (x * 2 <= count)
          a(x * 2)
          //          if (x * 2 + 1 <= count)
          a(x * 2 + 1)
      },
      go { case f(_, r) + done(x) => r(x) },
      go { case c((n, x)) + c((m, y)) =>
        val p = n + m
        val z = x + y
        if (p >= count)
          done(z)
        else
          c((n + m, z))
      }
    )

    a(1)
    f() shouldEqual (1 to count).map(i => i * i).sum

    tp.shutdownNow()
    tp1.shutdownNow()
    println(s"sum of $count numbers with nonlinear input patterns, branching emitters, and 1-thread site pool took ${elapsed(initTime)} ms")
  }

  it should "correctly process concurrent counters" in {
    // Same logic as Benchmark 1 but designed to catch race conditions more quickly.
    def make_counter_1(done: M[Unit], counters: Int, init: Int, reactionPool: Pool, sitePool: Pool): B[Unit, Unit] = {
      val c = m[Int]
      val d = b[Unit, Unit]

      site(reactionPool, sitePool)(
        go { case c(0) ⇒ done() },
        go { case c(x) + d(_, r) if x > 0 ⇒ c(x - 1); r() }
      )
      (1 to counters).foreach(_ ⇒ c(init))
      // We return just one molecule.
      d
    }

    val initTime = System.currentTimeMillis()
    var failures = 0
    val n = 1000
    val numberOfCounters = 10
    val count = 2

    (1 to n).foreach { _ ⇒
      val tp = new FixedPool(8)
      val tp1 = new FixedPool(1)

      val done = m[Unit]
      val all_done = m[Int]
      val f = b[Long, Long]

      site(tp)(
        go { case all_done(0) + f(tInit, r) ⇒ r(elapsed(tInit)) },
        go { case all_done(x) + done(_) if x > 0 ⇒ all_done(x - 1) }
      )
      val initialTime = System.currentTimeMillis()
      all_done(numberOfCounters)
      val d = make_counter_1(done, numberOfCounters, count, tp, tp1)
      // emit a blocking molecule `d` many times
      (1 to (count * numberOfCounters)).foreach(_ ⇒ d())
      val result = f.timeout(initialTime)(1.second)
      if (result.isEmpty) {
        failures += 1
      }
      tp1.shutdownNow()
      tp.shutdownNow()
    }
    println(s"concurrent counters correctness check: $numberOfCounters counters, count = $count, $n numbers, took ${elapsed(initTime)} ms")

    (if (failures > 0) s"Detected $failures failures out of $n tries" else "OK") shouldEqual "OK"
  }

  /** A binary operation on integers that is associative but not commutative.
    * See: F. J. Budden. A Non-Commutative, Associative Operation on the Reals. The Mathematical Gazette, Vol. 54, No. 390 (Dec., 1970), pp. 368-372
    * http://www.jstor.org/stable/3613855
    *
    * @param x First integer.
    * @param y Second integer.
    * @return Integer result.
    */
  def assocNonCommut(x: Int, y: Int): Int = {
    val s = 1 - math.abs(x % 2) * 2 // s = 1 if x is even, s = -1 if x is odd; math.abs is needed to fix the bug where (-1) % 2 == -1
    x + s * y
  }


  it should "perform ordered map-reduce using conditional reactions" in {
    // c((l, r, x)) represents the left-closed, right-open interval (l, r) over which we already performed the reduce operation, and the result value x.
    val c = m[(Int, Int, Int)]
    val done = m[Int]
    val f = b[Unit, Int]

    val count = 50000
    val nThreadsSite = 1

    val tp = new FixedPool(cpuCores)
    val tp1 = new FixedPool(nThreadsSite)
    val initTime = System.currentTimeMillis()

    site(tp, tp1)(
      go { case f(_, r) + done(x) => r(x) }
    )

    site(tp, tp1)(
      go { case c((l1, r1, x)) + c((l2, r2, y)) if r2 == l1 || l2 == r1 =>
        val l3 = math.min(l1, l2)
        val r3 = math.max(r1, r2)
        val z = if (l2 == r1) assocNonCommut(x, y) else assocNonCommut(y, x)
        if (r3 - l3 == count)
          done(z)
        else
          c((l3, r3, z))
      }
    )

    (1 to count).foreach(i => c((i, i + 1, i * i)))
    f() shouldEqual (1 to count).map(i => i * i).reduce(assocNonCommut)
    println(s"associative but non-commutative reduceB() onf $count numbers with nonlinear input patterns and $nThreadsSite-thread site pool took ${elapsed(initTime)} ms")
  }

  it should "perform ordered map-reduce using unique reactions (very slow)" in {
    // c((l, r, x)) represents the left-closed, right-open interval (l, r) over which we already performed the reduce operation, and the result value x.
    val done = m[Int]
    val f = b[Unit, Int]

    val count = 30
    val nThreadsSite = 1

    val tp = new FixedPool(cpuCores)
    val tp1 = new FixedPool(nThreadsSite)
    val initTime = System.currentTimeMillis()

    site(tp, tp1)(
      go { case f(_, r) + done(x) => r(x) }
    )

    // Define all molecule emitters as a 2-indexed map
    val emitters: Map[(Int, Int), M[Int]] =
      (0 until count).flatMap(i ⇒ (i + 1 to count).map(j ⇒ (i, j) → new M[Int](s"c[$i,$j]")))(scala.collection.breakOut)

    val lastMol = emitters((0, count))

    val reactions = (0 until count).flatMap(i ⇒ (i + 1 to count).flatMap(j ⇒ (j + 1 to count).map { k ⇒
      val mol1 = emitters((i, j))
      val mol2 = emitters((j, k))
      val mol3 = emitters((i, k))
      go { case mol1(x) + mol2(y) ⇒ mol3(assocNonCommut(x, y)) }
    })) :+ go { case lastMol(x) ⇒ done(x) }

    println(s"created emitters and reactions: at ${elapsed(initTime)} ms")

    site(tp, tp1)(reactions: _*)

    println(s"defined reactions: at ${elapsed(initTime)} ms")

    (1 to count).foreach(i ⇒ emitters((i - 1, i))(i * i))
    f() shouldEqual (1 to count).map(i => i * i).reduce(assocNonCommut)
    println(s"associative but non-commutative reduceB() on $count numbers with ${emitters.size} unique molecules, ${reactions.size} unique reactions, and $nThreadsSite-thread site pool took ${elapsed(initTime)} ms")
  }

}
