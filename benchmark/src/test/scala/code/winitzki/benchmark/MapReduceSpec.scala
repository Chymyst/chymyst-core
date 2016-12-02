package code.winitzki.benchmark

import java.time.LocalDateTime

import code.winitzki.benchmark.Common._
import code.winitzki.jc.FixedPool
import code.winitzki.jc.JoinRun._
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

}
