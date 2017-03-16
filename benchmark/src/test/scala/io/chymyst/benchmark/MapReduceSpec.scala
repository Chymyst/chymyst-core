package io.chymyst.benchmark

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import io.chymyst.jc._
import org.scalatest.{FlatSpec, Matchers}
import scala.concurrent.duration._

class MapReduceSpec extends FlatSpec with Matchers {

  def elapsed(initTime: LocalDateTime): Long = initTime.until(LocalDateTime.now, ChronoUnit.MILLIS)

  behavior of "map-reduce-like reactions"

  it should "perform a map/reduce-like computation" in {
    val count = 10

    val initTime = LocalDateTime.now

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

  it should "perform map-reduce as in tutorial" in {
    // declare the "map" and the "reduce" functions
    def f(x: Int): Int = x * x

    def reduceB(acc: Int, x: Int): Int = acc + x

    val arr = 1 to 100

    // declare molecule types
    val carrier = m[Int]
    val interm = m[Int]
    val accum = m[(Int, Int)]
    val fetch = b[Unit, Int]

    val tp = new FixedPool(4)

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
    tp.shutdownNow()
  }

  it should "compute the sum of numbers on molecules using nonlinear input pattern" in {
    val c = m[(Int, Int)]
    val done = m[Int]
    val f = b[Unit, Int]

    val count = 10000

    val tp = new FixedPool(cpuCores + 1)
    val tp1 = new FixedPool(1)
    val initTime = LocalDateTime.now

    site(tp, tp1)(
      go { case f(_, r) + done(x) => r(x) },
      go { case c((n, x)) + c((m, y)) =>
        val p = n + m
        val z = x + y
        if (p == count)
          done(z)
        else
          c((n + m, x + y))
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
    val initTime = LocalDateTime.now

    site(tp, tp1)(
      go { case f(_, r) + done(x) => r(x) },
      go { case c((n, x)) + c((m, y)) if x <= y =>
        val p = n + m
        val z = x + y
        if (p == count)
          done(z)
        else
          c((n + m, x + y))
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

    val count = 10000

    val tp = new FixedPool(cpuCores + 1)
    val tp1 = new FixedPool(1)
    val initTime = LocalDateTime.now

    site(tp, tp1)(
      go {
        case a(x) if x <= count ⇒
          c((1, x * x))
          // TODO: when these IF conditions are restored, performance improves 2x; this needs to be fixed
          // This also depends on discarding pipelined values that do not fit any conditions.
          if (x * 2 <= count) a(x * 2)
          if (x * 2 + 1 <= count) a(x * 2 + 1)
      },
      go { case f(_, r) + done(x) => r(x) },
      go { case c((n, x)) + c((m, y)) =>
        val p = n + m
        val z = x + y
        if (p >= count)
          done(z)
        else
          c((n + m, x + y))
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

    var failures = 0
    val n = 1000
    
    (1 to n).foreach { _ ⇒
      val numberOfCounters = 10
      val count = 2

      val tp = new FixedPool(8)
      val tp1 = new FixedPool(1)

      val done = m[Unit]
      val all_done = m[Int]
      val f = b[LocalDateTime, Long]

      site(tp)(
        go { case all_done(0) + f(tInit, r) ⇒ r(elapsed(tInit)) },
        go { case all_done(x) + done(_) if x > 0 ⇒ all_done(x - 1) }
      )
      val initialTime = LocalDateTime.now
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
    (if (failures > 0) s"Detected $failures failures out of $n tries" else "OK") shouldEqual "OK"
  }

}
