package code.chymyst.test

import java.util.concurrent.ConcurrentLinkedQueue

import code.chymyst.jc._
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

import scala.collection.JavaConverters.asScalaIteratorConverter

class Patterns02Spec extends FlatSpec with Matchers with BeforeAndAfterEach {

  var tp: Pool = _

  override def beforeEach(): Unit = {
    tp = new SmartPool(4)
  }

  override def afterEach(): Unit = {
    tp.shutdownNow()
  }

  behavior of "smokers"

  it should "implement smokers" in {
    val supplyLineSize = 10

    def smokingBreak(): Unit = Thread.sleep(math.floor(scala.util.Random.nextDouble * 20.0 + 2.0).toLong)

    case class ShippedInventory(tobacco: Int, paper: Int, matches: Int)
    // this data is only to demonstrate effects of randomization on the supply chain and make content of logFile more interesting.
    // strictly speaking all we need to keep track of is inventory. Example would work if pusher molecule value would carry Unit values instead.

    val pusher = m[ShippedInventory]
    // pusher means drug dealer, in classic Comp Sci, we'd call this producer or publisher.
    val count = m[Int]
    // giving description to the three E smokers molecules below makes for more vivid tracing, could be plainly m[Unit] instead.
    val Keith = new E("Keith obtained tobacco and matches to get his fix")
    val Slash = new E("Slash obtained tobacco and matches to get his fix")
    val Jimi = new E("Jimi obtained tobacco and matches to get his fix")

    val tobacco = m[ShippedInventory] // this is not particularly elegant, ideally this should carry Unit but pusher needs to obtain current state
    val matches = m[ShippedInventory]
    val paper = m[ShippedInventory]

    val check = new EE("check") // blocking Unit, only blocking molecule of the example.

    val logFile = new ConcurrentLinkedQueue[String]

    def enjoyAndResume(s: ShippedInventory) = {
      smokingBreak()
      pusher(s)
    }

    site(tp)(
      go { case pusher(ShippedInventory(t, p, m)) + count(n) if n >= 1 =>
        logFile.add(s"$n,$t,$p,$m") // logging the state makes it easier to see what's going on, curious user may put println here instead.
        scala.util.Random.nextInt(3) match {
          // select the 2 ingredients randomly
          case 0 =>
            val s = ShippedInventory(t + 1, p, m + 1)
            tobacco(s);
            matches(s)
          case 1 =>
            val s = ShippedInventory(t + 1, p + 1, m)
            tobacco(s);
            paper(s)
          case _ =>
            val s = ShippedInventory(t, p + 1, m + 1)
            matches(s);
            paper(s)
        }
        count(n - 1)
      },
      go { case count(0) + check(_, r) => r() },

      go { case Keith(_) + tobacco(s) + matches(_) => enjoyAndResume(s); Keith() },
      go { case Slash(_) + tobacco(s) + paper(_) => enjoyAndResume(s); Slash() },
      go { case Jimi(_) + matches(s) + paper(_) => enjoyAndResume(s); Jimi() }
    )

    Keith(()) + Slash(()) + Jimi(())
    pusher(ShippedInventory(0, 0, 0))
    count(supplyLineSize) // if running as a daemon, we would not use count and let the example/application run for ever.

    check()
    val result = logFile.iterator().asScala.toSeq
    (0 until supplyLineSize).foreach { i =>
      val current: Array[String] = result(i).split(',')
      List(current(1).toInt, current(2).toInt, current(3).toInt).sum shouldEqual 2 * i // # ingredients handed out at each cycle is twice number of cycles
      current(0).toInt + i shouldEqual supplyLineSize // # cycles outstanding + cycles ran should be 10.
    }
  }

  it should "implement generalized smokers" in {
    val supplyLineSize = 10

    def waitSome(): Unit = Thread.sleep(math.floor(scala.util.Random.nextDouble * 20.0 + 2.0).toLong)

    def smokingBreak(): Unit = waitSome()

    case class ShippedInventory(tobacco: Int, paper: Int, matches: Int)
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

    site(tp)(
      go { case pusher(ShippedInventory(t, p, m)) + count(n) if n >= 1 =>
        logFile.add(s"$n,$t,$p,$m")
        var s = ShippedInventory(t, p, m)
        scala.util.Random.nextInt(3) match {
          // select the 2 ingredients randomly
          case 0 =>
            s = ShippedInventory(t + 1, p, m + 1)
            tobacco();
            matches()
          case 1 =>
            s = ShippedInventory(t + 1, p + 1, m)
            tobacco();
            paper()
          case _ =>
            s = ShippedInventory(t, p + 1, m + 1)
            matches();
            paper()
        }
        waitSome()
        pusher(s)
        count(n - 1)
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
    pusher(ShippedInventory(0, 0, 0))
    count(supplyLineSize)

    check()
    val result = logFile.iterator().asScala.toSeq
    (0 until supplyLineSize).foreach { i =>
      val current: Array[String] = result(i).split(',')
      List(current(1).toInt, current(2).toInt, current(3).toInt).sum shouldEqual (2 * i) // # ingredients handed out at each cycle is twice number of cycles
      current(0).toInt + i shouldEqual supplyLineSize // # cycles outstanding + cycles ran should be 10.
    }
  }

}