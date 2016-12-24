package code.chymyst.test

import java.util.concurrent.ConcurrentLinkedQueue

import code.chymyst.jc._
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}
import scala.collection.JavaConverters.asScalaIteratorConverter
import scala.concurrent.duration._
import scala.language.postfixOps

class Patterns01Spec extends FlatSpec with Matchers with BeforeAndAfterEach {

  var tp: Pool = _

  override def beforeEach(): Unit = {
    tp = new SmartPool(4)
  }

  override def afterEach(): Unit = {
    tp.shutdownNow()
  }

  behavior of "Chymyst"

  it should "implement barrier (rendezvous without data exchange) for two processes" in {
    val barrier1 = b[Unit,Unit]
    val barrier2 = b[Unit,Unit]

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

  it should "implement exchanger (rendezvous with data exchange) for two processes" in {
    val barrier1 = b[Int,Int]
    val barrier2 = b[Int,Int]

    val begin1 = m[Unit]
    val begin2 = m[Unit]

    val end1 = m[Int]
    val end2 = m[Int]
    val done = b[Unit,(Int,Int)]

    site(tp)(
      go { case begin1(_) =>
        val x1 = 123  // some computation
        val y1 = barrier1(x1) // receive value from Process 2
        val z = y1 * y1 // further computation
        end1(z)
      }
    )

    site(tp)(
      go { case begin2(_) =>
        val x2 = 456 // some computation
        val y2 = barrier2(x2) // receive value from Process 1
        val z = y2 * y2 // further computation
        end2(z)
      }
    )

    site(tp)(
      // The values are exchanged in this reaction.
      go { case barrier1(x1, r1) + barrier2(x2, r2) => r1(x2); r2(x1) }
    )

    site(tp)(
      go { case end1(x1) + end2(x2) + done(_, r) => r((x1,x2))}
    )

    begin1() + begin2() // emit both molecules to enable starting the two reactions

    val result = done()
    result shouldEqual ((456*456,123*123))
  }

  it should "implement barrier (rendezvous without data exchange) with 4 processes" in {
    val barrier1 = b[Unit,Unit]
    val barrier2 = b[Unit,Unit]
    val barrier3 = b[Unit,Unit]
    val barrier4 = b[Unit,Unit]

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
      go { case begin1(_) => f1(); barrier1(); g1(); end1() },
      go { case begin2(_) => f2(); barrier2(); g2(); end2() },
      go { case begin3(_) => f3(); barrier3(); g3(); end3() },
      go { case begin4(_) => f4(); barrier4(); g4(); end4() },
      go { case barrier1(_, r1) + barrier2(_, r2) + barrier3(_, r3) + barrier4(_, r4) => r1(); r2(); r3(); r4() },
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

    // There will be n blocked threads. We need one more thread to run the reaction site and one more thread to count down.
    val pool = new FixedPool(n+2)

    val barrier = b[Unit,Unit]
    val counterInit = m[Unit]
    val counter = b[Int,Unit]
    val endCounter = m[Int]
    val begin = m[(()=>Unit, ()=>Unit)]
    val end = m[Unit]
    val done = b[Unit, Unit]

    val logFile = new ConcurrentLinkedQueue[String]

    def f(n: Int)(): Unit = { logFile.add(s"f$n"); () }
    def g(n: Int)(): Unit = { logFile.add(s"g$n"); () }

    site(pool)(
      go { case begin((f,g)) => f(); barrier(); g(); end() },
      go { case barrier(_, replyB) + counterInit(_) => if (n > 1) counter(n-1); replyB() },
      go { case barrier(_, replyB) + counter(k, replyC) => replyC(); if (k > 1) counter(k-1); replyB() },
      go { case end(_) + endCounter(k) => endCounter(k-1) },
      go { case endCounter(0) + done(_, r) => r()}
    )

    (1 to n).foreach(i => begin((f(i),g(i))))
    counterInit()
    endCounter(n)
    done.timeout(1000 millis)() shouldEqual Some(())

    val result: Seq[String] = logFile.iterator().asScala.toSeq
    result.size shouldEqual 2*n
    // Now, there must be f_1, ..., f_n (in any order) before g_1, ..., g_n (also in any order).
    // We use sets to verify this.

    val setF = (0 to n-1).map(result.apply).toSet
    val setG = (n to 2*n-1).map(result.apply).toSet

    val expectedSetF = (1 to n).map(i => s"f$i").toSet
    val expectedSetG = (1 to n).map(i => s"g$i").toSet

    setF diff expectedSetF shouldEqual Set()
    setG diff expectedSetG shouldEqual Set()

    setF shouldEqual expectedSetF
    setG shouldEqual expectedSetG

    pool.shutdownNow()
  }

}
