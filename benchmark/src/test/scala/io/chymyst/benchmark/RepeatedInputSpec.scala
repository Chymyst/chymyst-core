package io.chymyst.benchmark

import io.chymyst.jc._
import org.scalatest.{FlatSpec, Matchers}
import io.chymyst.test.Common.{elapsedTimeMs, litmus}
import scala.util.Random.nextInt

class RepeatedInputSpec extends FlatSpec with Matchers {

  behavior of "reactions with repeated input"

  it should "handle cross-molecule guard with constant values" in {
    val k = 5
    val total = 1000
    val repetitions = 20

    val (_, elapsed) = elapsedTimeMs(
      (1 to repetitions).foreach { i =>
        withPool(new FixedPool(8)) { tp =>
          val a = m[Option[Int]]
          val done = m[Int]
          val (all_done, f) = litmus[Boolean](tp)
          site(tp)(
            go { case a(Some(1)) + a(Some(2)) + a(Some(3)) + a(Some(4)) + a(Some(5)) + a(Some(x)) if x > k => done(1) },
            go { case done(x) + done(y) => if (x + y < total) done(x + y) else all_done(true) }
          )

          (1 to total).foreach(_ => a(Some(1)) + a(Some(2)) + a(Some(3)) + a(Some(4)) + a(Some(5)) + a(Some(k * 2)))
          f()
        }.get shouldEqual true
      }
    )
    println(s"Repeated input 1: total = $total, repetitions = $repetitions, elapsed = $elapsed ms")
  }

  it should "handle cross-molecule guard with effectively constant values" in {
    val k = 5
    val total = 1000
    val repetitions = 20

    val (_, elapsed) = elapsedTimeMs(
      (1 to repetitions).foreach { i =>
        withPool(new FixedPool(8)) { tp =>
          val a = m[Option[Int]]
          val done = m[Int]
          val (all_done, f) = litmus[Boolean](tp)
          site(tp)(
            go {
              case a(Some(x1)) + a(Some(x2)) + a(Some(x3)) + a(Some(x4)) + a(Some(x5)) + a(Some(x))
                if x > k && x1 == 1 && x2 == 2 && x3 == 3 && x4 == 4 && x5 == 5
              => done(1)
            },
            go { case done(x) + done(y) => if (x + y < total) done(x + y) else all_done(true) }
          )

          (1 to total).foreach(_ => a(Some(1)) + a(Some(2)) + a(Some(3)) + a(Some(4)) + a(Some(5)) + a(Some(k * 2)))
          f()
        }.get shouldEqual true
      }
    )
    println(s"Repeated input 2: total = $total, repetitions = $repetitions, elapsed = $elapsed ms")
  }

  it should "handle cross-molecule guard with simple effectively constant values" in {
    val k = 5
    val total = 1000
    val repetitions = 20

    val (_, elapsed) = elapsedTimeMs(
      (1 to repetitions).foreach { i =>
        withPool(new FixedPool(8)) { tp =>
          val a = m[Int]
          val done = m[Int]
          val (all_done, f) = litmus[Boolean](tp)
          site(tp)(
            go {
              case a(x1) + a(x2) + a(x3) + a(x4) + a(x5) + a(x)
                if x > k && x1 == 1 && x2 == 2 && x3 == 3 && x4 == 4 && x5 == 5
              => done(1)
            },
            go { case done(x) + done(y) => if (x + y < total) done(x + y) else all_done(true) }
          )

          (1 to total).foreach(_ => a(1) + a(2) + a(3) + a(4) + a(5) + a(k * 2))
          f()
        }.get shouldEqual true
      }
    )
    println(s"Repeated input 3: total = $total, repetitions = $repetitions, elapsed = $elapsed ms")
  }

  it should "handle cross-molecule guard with simple constant values" in {
    val k = 5
    val total = 1000
    val repetitions = 20

    val (_, elapsed) = elapsedTimeMs(
      (1 to repetitions).foreach { i =>
        withPool(new FixedPool(8)) { tp =>
          val a = m[Int]
          val done = m[Int]
          val (all_done, f) = litmus[Boolean](tp)
          site(tp)(
            go {
              case a(1) + a(2) + a(3) + a(4) + a(5) + a(x)
                if x > k
              => done(1)
            },
            go { case done(x) + done(y) => if (x + y < total) done(x + y) else all_done(true) }
          )

          (1 to total).foreach(_ => a(1) + a(2) + a(3) + a(4) + a(5) + a(k * 2))
          f()
        }.get shouldEqual true
      }
    )
    println(s"Repeated input 4: total = $total, repetitions = $repetitions, elapsed = $elapsed ms")
  }

  it should "handle cross-molecule guard with simple constant values and inert values" in {
    val k = 5
    val total = 200
    val repetitions = 20

    val (_, elapsed) = elapsedTimeMs(
      (1 to repetitions).foreach { i =>
        withPool(new FixedPool(8)) { tp =>
          val a = m[Int]
          val done = m[Int]
          val (all_done, f) = litmus[Boolean](tp)
          site(tp)(
            go {
              case a(1) + a(2) + a(3) + a(4) + a(5) + a(x)
                if x > k
              => done(1)
            },
            go { case done(x) + done(y) => if (x + y < total) done(x + y) else all_done(true) }
          )

          (1 to total).foreach(_ => a(-100) + a(1) + a(2) + a(3) + a(4) + a(5) + a(k * 2))
          f()
        }.get shouldEqual true
      }
    )
    println(s"Repeated input 5: total = $total, repetitions = $repetitions, elapsed = $elapsed ms")
  }

  it should "handle cross-molecule guard with nonconstant values" in {
    val k = 4
    val total = 5
    val repetitions = 10

    def getHash(xs: Seq[Long]): Long = {
      xs.fold(0L)(_ * k + _)
    }

    getHash(Seq(1, 2, 3)) shouldEqual 3 + k * (2 + k * 1)

    val (_, elapsed) = elapsedTimeMs(
      (1 to repetitions).foreach { i =>
        //        println(s"iteration $i")
        withPool(new FixedPool(8)) { tp =>
          val a = m[Long]
          val done = m[Int]
          val (all_done, f) = litmus[Boolean](tp)
          site(tp)(
            go { case a(x1) + a(x2) + a(x3) + a(x4) + a(y) if getHash(Seq(x1, x2, x3, x4)) == y => done(1) },
            go { case done(x) + done(y) => if (x + y < total) done(x + y) else all_done(true) }
          )
          (1 to total).foreach { i =>
            val data = (1 to k).map(i => nextInt.toLong)
            val y = getHash(data)
            a(y)
            data.foreach(a)
          }
          f()
        }.get shouldEqual true
      }
    )
    println(s"Repeated input 6: total = $total, repetitions = $repetitions, elapsed = $elapsed ms")
  }

}
