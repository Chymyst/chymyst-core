package io.chymyst.test

import java.util.concurrent.ConcurrentLinkedQueue

import io.chymyst.jc._
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}
import scala.collection.JavaConverters.asScalaIteratorConverter
import scala.concurrent.duration._
import scala.language.postfixOps

class Patterns01Spec extends FlatSpec with Matchers with BeforeAndAfterEach {

  var tp: Pool = _

  override def beforeEach(): Unit = {
    tp = new SmartPool(8)
  }

  override def afterEach(): Unit = {
    tp.shutdownNow()
  }

  behavior of "Chymyst"

  it should "implement exchanger (rendezvous with data exchange) with 2 processes and 1 barrier() molecule" in {
    val barrier = b[Int, Int]

    val begin1 = m[Unit]
    val begin2 = m[Unit]

    val end1 = m[Int]
    val end2 = m[Int]
    val done = b[Unit, (Int, Int)]

    site(tp)(
      go { case begin1(_) =>
        val x1 = 123
        // some computation
        val y1 = barrier(x1)
        // receive value from Process 2
        val z = y1 * y1 // further computation
        end1(z)
      }
    )

    site(tp)(
      go { case begin2(_) =>
        val x2 = 456
        // some computation
        val y2 = barrier(x2)
        // receive value from Process 1
        val z = y2 * y2 // further computation
        end2(z)
      }
    )

    site(tp)(
      // The values are exchanged in this reaction.
      go { case barrier(x1, r1) + barrier(x2, r2) => r1(x2); r2(x1) }
    )

    site(tp)(
      go { case end1(x1) + end2(x2) + done(_, r) => r((x1, x2)) }
    )

    begin1() + begin2() // emit both molecules to enable starting the two reactions

    val result = done()
    result shouldEqual ((456 * 456, 123 * 123))
  }

  it should "implement barrier (rendezvous without data exchange) with 2 processes and 2 barrier() molecules" in {
    val barrier1 = b[Unit, Unit]
    val barrier2 = b[Unit, Unit]

    val begin1 = m[Unit]
    val begin2 = m[Unit]

    val end1 = m[Unit]
    val end2 = m[Unit]
    val done = b[Unit, Unit]

    val logFile = new ConcurrentLinkedQueue[String]

    def f1() = logFile.add("f1")

    def f2() = logFile.add("f2")

    def g1() = logFile.add("g1")

    def g2() = logFile.add("g2")

    site(tp)(
      go { case begin1(_) => f1(); barrier1(); g1(); end1() },
      go { case begin2(_) => f2(); barrier2(); g2(); end2() },
      go { case barrier1(_, r1) + barrier2(_, r2) => r1(); r2() },
      go { case end1(_) + end2(_) + done(_, r) => r() }
    )

    begin1() + begin2()
    done()
    val result: Seq[String] = logFile.iterator().asScala.toSeq
    // Now, there must be f1 and f2 (in any order) before g1 and g2 (also in any order).
    // We use `Set` to verify this.
    result.size shouldEqual 4
    Set(result(0), result(1)) shouldEqual Set("f1", "f2")
    Set(result(2), result(3)) shouldEqual Set("g1", "g2")
  }

  it should "implement barrier (rendezvous without data exchange) with 4 processes and 1 barrier() molecule" in {
    val barrier = b[Unit, Unit]

    val begin1 = m[Unit]
    val begin2 = m[Unit]
    val begin3 = m[Unit]
    val begin4 = m[Unit]

    val end1 = m[Unit]
    val end2 = m[Unit]
    val end3 = m[Unit]
    val end4 = m[Unit]
    val done = b[Unit, Unit]

    val logFile = new ConcurrentLinkedQueue[String]

    def f1() = logFile.add("f1")

    def f2() = logFile.add("f2")

    def f3() = logFile.add("f4")

    def f4() = logFile.add("f3")

    def g1() = logFile.add("g1")

    def g2() = logFile.add("g2")

    def g3() = logFile.add("g4")

    def g4() = logFile.add("g3")

    site(tp)(
      go { case begin1(_) => f1(); barrier(); g1(); end1() },
      go { case begin2(_) => f2(); barrier(); g2(); end2() },
      go { case begin3(_) => f3(); barrier(); g3(); end3() },
      go { case begin4(_) => f4(); barrier(); g4(); end4() },
      go { case barrier(_, r1) + barrier(_, r2) + barrier(_, r3) + barrier(_, r4) => r1(); r2(); r3(); r4() },
      go { case end1(_) + end2(_) + end3(_) + end4(_) + done(_, r) => r() }
    )

    begin1() + begin2() + begin3() + begin4()
    done()
    val result: Seq[String] = logFile.iterator().asScala.toSeq
    // Now, there must be f1 and f2 (in any order) before g1 and g2 (also in any order).
    // We use `Set` to verify this.
    result.size shouldEqual 8
    (0 to 3).map(result).toSet shouldEqual Set("f1", "f2", "f3", "f4")
    (4 to 7).map(result).toSet shouldEqual Set("g1", "g2", "g3", "g4")
  }

  it should "implement barrier (rendezvous without data exchange) with n processes" in {

    val n = 100 // The number of rendezvous participants needs to be known in advance, or else we don't know how long still to wait for rendezvous.

    // There will be 2*n blocked threads; the test will fail with FixedPool(2*n-1).
    withPool(new FixedPool(2 * n)) { pool =>

      val barrier = b[Unit, Unit]
      val counterInit = m[Unit]
      val counter = b[Int, Unit]
      val endCounter = m[Int]
      val begin = m[(() => Unit, () => Unit)]
      val end = m[Unit]
      val done = b[Unit, Unit]

      val logFile = new ConcurrentLinkedQueue[String]

      def f(n: Int)(): Unit = {
        logFile.add(s"f$n")
        ()
      }

      def g(n: Int)(): Unit = {
        logFile.add(s"g$n")
        ()
      }

      site(pool)(
        go { case begin((f, g)) => f(); barrier(); g(); end() }, // this reaction will be run n times because we emit n molecules `begin` with various `f` and `g`
        go { case barrier(_, replyB) + counterInit(_) => // this reaction will consume the very first barrier molecule emitted
          counter(1) // one reaction has reached the rendezvous point
          replyB()
        },
        go { case barrier(_, replyB) + counter(k, replyC) => // the `counter` molecule holds the number (k) of the reactions that have reached the rendezvous before this reaction started.
          if (k + 1 < n) counter(k + 1); else println(s"rendezvous passed by $n reactions")
          replyC() // `replyC()` must be here. Doing `replyC()` before emitting `counter(k+1)` would have unblocked some reactions and allowed them to proceed beyond the rendezvous point without waiting for all others.
          replyB()
        },
        go { case end(_) + endCounter(k) => endCounter(k - 1) },
        go { case endCounter(0) + done(_, r) => r() }
      )

      (1 to n).foreach(i => begin((f(i), g(i))))
      counterInit()
      endCounter(n)
      done.timeout()(1000 millis) shouldEqual Some(())

      val result: Seq[String] = logFile.iterator().asScala.toSeq
      result.size shouldEqual 2 * n
      // Now, there must be f_1, ..., f_n (in any order) before g_1, ..., g_n (also in any order).
      // We use sets to verify this.

      val setF = (0 until n).map(result.apply).toSet
      val setG = (n until 2 * n).map(result.apply).toSet

      val expectedSetF = (1 to n).map(i => s"f$i").toSet
      val expectedSetG = (1 to n).map(i => s"g$i").toSet

      setF diff expectedSetF shouldEqual Set()
      setG diff expectedSetG shouldEqual Set()

      expectedSetF diff setF shouldEqual Set()
      expectedSetG diff setG shouldEqual Set()
    }.get
  }

  // This test will fail and will need to be rewritten when automatic pipelining is implemented,
  // because `manL`, `womanL`, and `beginDancing` will then be pipelined and consumed in strict order.
  it should "implement dance pairing without queue labels" in {
    val man = m[Unit]
    val manL = m[Int]
    val queueMen = m[Int]
    val woman = m[Unit]
    val womanL = m[Int]
    val queueWomen = m[Int]
    val beginDancing = b[Int, Unit]

    val danceCounter = m[List[Int]]
    val done = b[Unit, List[Int]]

    val total = 100

    site(tp)(
      go { case danceCounter(x) + done(_, r) if x.size == total => r(x) + danceCounter(x) },
      go { case beginDancing(xy, r) + danceCounter(x) => danceCounter(x :+ xy) + r() },
      go { case _ => danceCounter(Nil) }
    )

    site(tp)(
      go { case man(_) + queueMen(n) => queueMen(n + 1) + manL(n) },
      go { case woman(_) + queueWomen(n) => queueWomen(n + 1) + womanL(n) },
      go { case manL(xy) + womanL(xx) => beginDancing(Math.min(xx,xy)) },
      go { case _ => queueMen(0) + queueWomen(0) }
    )

    (0 until total/2).foreach(_ => man())
    danceCounter.volatileValue shouldEqual Nil
    (0 until total/2).foreach(_ => man() + woman())
    (0 until total/2).foreach(_ => woman())

    val ordering = done()
    println(s"Dance pairing without queue labels yields $ordering")
    ordering should not equal (0 until total).toList // Dancing queue order cannot be observed.
  }

  it should "implement dance pairing with queue labels" in {
    val man = m[Unit]
    val manL = m[Int]
    val queueMen = m[Int]
    val woman = m[Unit]
    val womanL = m[Int]
    val queueWomen = m[Int]
    val beginDancing = b[Int, Unit]
    val mayBegin = m[Int]

    val danceCounter = m[List[Int]]
    val done = b[Unit, List[Int]]

    val total = 100

    site(tp)(
      go { case danceCounter(x) + done(_, r) if x.size == total => r(x) + danceCounter(x) },
      go { case beginDancing(xy, r) + danceCounter(x) => danceCounter(x :+ xy) + r() },
      go { case _ => danceCounter(Nil) }
    )

    site(tp)(
      go { case man(_) + queueMen(n) => queueMen(n + 1) + manL(n) },
      go { case woman(_) + queueWomen(n) => queueWomen(n + 1) + womanL(n) },
      go { case manL(xy) + womanL(xx) + mayBegin(l) if xx == xy && xy == l => beginDancing(l); mayBegin(l + 1) },
      go { case _ => queueMen(0) + queueWomen(0) + mayBegin(0) }
    )

    (1 to total).foreach(_ => man())
    danceCounter.volatileValue shouldEqual Nil
    (1 to total).foreach(_ => woman())
    done() shouldEqual (0 until total).toList // Dancing queue order must be observed.
  }

}
