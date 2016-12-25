package code.chymyst.test

import java.util.concurrent.ConcurrentLinkedQueue

import code.chymyst.jc.{M, _}
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

  it should "implement smokers" in {
    val supplyLineSize = 10
    def smokingBreak(): Unit = Thread.sleep(math.floor(scala.util.Random.nextDouble*20.0 + 2.0).toLong)

    case class ShippedInventory(tobacco: Int, paper: Int, matches: Int)
    // this data is only to demonstrate effects of randomization on the supply chain and make content of logFile more interesting.
    // strictly speaking all we need to keep track of is inventory. Example would work if pusher molecule value would carry Unit values instead.

    val pusher = m[ShippedInventory] // pusher means drug dealer, in classic Comp Sci, we'd call this producer or publisher.
    val count = m[Int]
    val Keith = new E("Keith obtained tobacco and matches to get his fix") // makes for more vivid tracing, could be plainly m[Unit]
    val Slash = new E("Slash obtained tobacco and matches to get his fix") // same
    val Jimi = new E("Jimi obtained tobacco and matches to get his fix") // same

    val tobacco = m[ShippedInventory] // this is not particularly elegant, ideally this should carry Unit but pusher needs to obtain current state
    val matches = m[ShippedInventory] // same
    val paper = m[ShippedInventory] // same

    val check = new EE("check") // blocking Unit, only blocking molecule of the example.

    val logFile = new ConcurrentLinkedQueue[String]

    def enjoyAndResume(s: ShippedInventory) = {
      smokingBreak()
      pusher(s)
    }

    site(tp) (
      go { case pusher(ShippedInventory(t, p, m)) + count(n) if n >= 1 =>
        logFile.add(s"$n,$t,$p,$m") // logging the state makes it easier to see what's going on, curious user may put println here instead.
        scala.util.Random.nextInt(3) match { // select the 2 ingredients randomly
          case 0 =>
            val s = ShippedInventory(t+1, p, m+1)
            tobacco(s); matches(s)
          case 1 =>
            val s =  ShippedInventory(t+1, p+1, m)
            tobacco(s); paper(s)
          case _ =>
            val s = ShippedInventory(t, p+1, m+1)
            matches(s); paper(s)
        }
        count(n-1)
      },
      go { case count(0) + check(_, r) => r() },

      go { case Keith(_) + tobacco(s) + matches(_) => enjoyAndResume(s); Keith() },
      go { case Slash(_) + tobacco(s) + paper(_) => enjoyAndResume(s); Slash() },
      go { case Jimi(_) + matches(s) + paper(_) => enjoyAndResume(s); Jimi()}
    )

    Keith(()) + Slash(()) + Jimi(())
    pusher(ShippedInventory(0,0,0))
    count(supplyLineSize) // if running as a daemon, we would not use count and let the example/application run for ever.

    check()
    val result = logFile.iterator().asScala.toSeq
    (0 until supplyLineSize).foreach { i =>
        val current: Array[String] = result(i).split(',')
        List(current(1).toInt, current(2).toInt, current(3).toInt).sum shouldEqual 2*i // # ingredients handed out at each cycle is twice number of cycles
        current(0).toInt + i shouldEqual supplyLineSize // # cycles outstanding + cycles ran should be 10.
    }
  }

  it should "implement generalized smokers" in {
    val supplyLineSize = 10
    def waitSome(): Unit = Thread.sleep(math.floor(scala.util.Random.nextDouble*20.0 + 2.0).toLong)
    def smokingBreak(): Unit = waitSome()

    case class ShippedInventory(tobacco: Int, paper: Int, matches: Int)
    // case class SupplyChainState(inventory: Int, shipped: ShippedInventory)
    // this data is only to demonstrate effects of randomization on the supply chain and make content of logFile more interesting.
    // strictly speaking all we need to keep track of is inventory.

    val pusher = m[ShippedInventory]
    val count = m[Int]
    val Keith = new E("Keith obtained tobacco and matches to get his fix")
    val Slash = new E("Slash obtained tobacco and paper to get his fix")
    val Jimi = new E("Jimi obtained matches and paper to get his fix")

    val tobacco = m[Unit]
    val matches = m[Unit]
    val paper = m[Unit]

    val check = new EE("check") // blocking Unit, only blocking molecule of the example.

    val logFile = new ConcurrentLinkedQueue[String]

    site(tp) (
      go { case pusher(ShippedInventory(t, p, m)) + count(n) if n >= 1  =>
        logFile.add(s"$n,$t,$p,$m")
        var s = ShippedInventory(t, p, m)
        scala.util.Random.nextInt(3) match { // select the 2 ingredients randomly
          case 0 =>
            s = ShippedInventory(t+1, p, m+1)
            tobacco(); matches()
          case 1 =>
            s = ShippedInventory(t+1, p+1, m)
            tobacco(); paper()
          case _ =>
            s = ShippedInventory(t, p+1, m+1)
            matches(); paper()
        }
        waitSome()
        pusher(s)
        count(n-1)
      },
      go { case count(0) + check(_, r) => r() },

      go { case Keith(_) + tobacco(_) + matches(_) =>
        println(Keith); smokingBreak(); Keith() // for tidy output, may suppress the println here.
      },
      go { case Slash(_) + tobacco(_) + paper(_) =>
        println(Slash); smokingBreak(); Slash()
      },
      go { case Jimi(_) + matches(_) + paper(_) =>
        println(Jimi); smokingBreak(); Jimi()
      }
    )

    Keith(()) + Slash(()) + Jimi(())
    pusher(ShippedInventory(0,0,0))
    count(supplyLineSize)

    check()
    val result = logFile.iterator().asScala.toSeq
    (0 until supplyLineSize).foreach { i =>
      val current: Array[String] = result(i).split(',')
      List(current(1).toInt, current(2).toInt, current(3).toInt).sum shouldEqual(2*i) // # ingredients handed out at each cycle is twice number of cycles
      current(0).toInt + i shouldEqual supplyLineSize // # cycles outstanding + cycles ran should be 10.
    }
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

    // There will be 2*n blocked threads; the test will fail with FixedPool(2*n-1).
    val pool = new FixedPool(2*n)

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
      go { case begin((f,g)) => f(); barrier(); g(); end() }, // this reaction will be run n times because we emit n molecules `begin` with various `f` and `g`
      go { case barrier(_, replyB) + counterInit(_) => // this reaction will consume the very first barrier molecule emitted
        counter(1) // one reaction has reached the rendezvous point
        replyB()
      },
      go { case barrier(_, replyB) + counter(k, replyC) => // the `counter` molecule holds the number (k) of the reactions that have reached the rendezvous before this reaction started.
        if (k + 1 < n) counter(k+1); else println(s"rendezvous passed by $n reactions")
        replyC() // `replyC()` must be here. Doing `replyC()` before emitting `counter(k+1)` would have unblocked some reactions and allowed them to proceed beyond the rendezvous point without waiting for all others.
        replyB()
      },
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

    expectedSetF diff setF shouldEqual Set()
    expectedSetG diff setG shouldEqual Set()

    pool.shutdownNow()
  }

}
