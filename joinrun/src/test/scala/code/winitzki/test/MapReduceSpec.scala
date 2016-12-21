package code.winitzki.test

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import code.winitzki.jc.FixedPool
import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Macros._
import org.scalatest.{FlatSpec, Matchers}

class MapReduceSpec extends FlatSpec with Matchers {

  def elapsed(initTime: LocalDateTime): Long = initTime.until(LocalDateTime.now, ChronoUnit.MILLIS)

  it should "perform a map/reduce-like computation" in {
    val count = 10

    val initTime = LocalDateTime.now

    val res = m[List[Int]]
    val r = m[Int]
    val d = m[Int]
    val get = b[Unit, List[Int]]

    val tp = new FixedPool(4)
    site(tp)(
      go { case d(n) => r(n*2) },
      go { case res(list) + r(s) => res(s::list) },
      go { case get(_, reply) + res(list) if list.size == count => reply(list) }
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
    def f(x: Int): Int = x*x
    def reduceB(acc: Int, x: Int): Int = acc + x

    val arr = 1 to 100

    // declare molecule types
    val carrier = m[Int]
    val interm = m[Int]
    val accum = m[(Int,Int)]
    val fetch = b[Unit,Int]

    val tp = new FixedPool(4)

    // declare the reaction for "map"
    site(tp)(
      go { case carrier(x) => val res = f(x); interm(res) }
    )

    // reactions for "reduce" must be together since they share "accum"
    site(tp)(
      go { case accum((n, b)) + interm(res) =>
        accum((n+1, reduceB(b, res) ))
      },
      go { case accum((n, b)) + fetch(_, reply) if n == arr.size => reply(b) }
    )

    // emit molecules
    accum((0, 0))
    arr.foreach(i => carrier(i))
    val result = fetch()
    result shouldEqual arr.map(f).reduce(reduceB) // 338350
  }

}
