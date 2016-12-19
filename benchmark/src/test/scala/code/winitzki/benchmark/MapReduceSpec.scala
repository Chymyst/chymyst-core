package code.winitzki.benchmark

import java.time.LocalDateTime

import code.winitzki.benchmark.Common._
import code.winitzki.jc.FixedPool
import code.winitzki.jc.JoinRun._
import code.winitzki.jc.Macros.{run => &}
import code.winitzki.jc.Macros._
import org.scalatest.{FlatSpec, Matchers}

class MapReduceSpec extends FlatSpec with Matchers {

  it should "perform a map/reduce-like computation" in {
    val count = 10

    val initTime = LocalDateTime.now

    val res = m[List[Int]]
    val r = m[Int]
    val d = m[Int]
    val get = b[Unit, List[Int]]

    val tp = new FixedPool(4)
    join(tp)(
      &{ case d(n) => r(n*2) },
      &{ case res(list) + r(s) => res(s::list) },
      &{ case get(_, reply) + res(list) if list.size == count => reply(list) }
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
    join(tp)(
      & { case carrier(a) => val res = f(a); interm(res) }
    )

    // reactions for "reduce" must be together since they share "accum"
    join(tp)(
      & { case accum((n, b)) + interm(res) if n > 0 =>
        accum((n+1, reduceB(b, res) ))
      },
      & { case accum((0, _)) + interm(res) => accum((1, res)) },
      & { case accum((n, b)) + fetch(_, reply) if n == arr.size => reply(b) }
    )

    // inject molecules
    accum((0, 0))
    arr.foreach(i => carrier(i))
    val result = fetch()
    result shouldEqual 338350
  }

}
