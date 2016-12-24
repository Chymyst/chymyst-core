package code.chymyst.test

import java.util.concurrent.ConcurrentLinkedQueue

import scala.collection.JavaConverters.asScalaIteratorConverter

import code.chymyst.jc._
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

class Patterns01Spec extends FlatSpec with Matchers with BeforeAndAfterEach {

  var tp: Pool = _

  override def beforeEach(): Unit = {
    tp = new SmartPool(4)
  }

  override def afterEach(): Unit = {
    tp.shutdownNow()
  }

  behavior of "Chymyst"

  it should "implement rendezvous" in {
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
    // now, there must be f1 and f2 (in any order) before g1 and g2 (also in any order)
    result.size shouldEqual 4
    Set(result(0), result(1)) shouldEqual Set("f1", "f2")
    Set(result(2), result(3)) shouldEqual Set("g1", "g2")
  }

  it should "implement exchanger" in {
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
        val y2 = barrier2(x2) // receive value from Process 2
        val z = y2 * y2 // further computation
        end2(z)
      }
    )

    site(tp)(
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
    def smoke(): Unit = Thread.sleep(math.floor(scala.util.Random.nextDouble*20.0 + 2.0).toLong)

    case class ShippedInventory(tobacco: Int, paper: Int, matches: Int)
    case class SupplyChainState(inventory: Int, shipped: ShippedInventory)

    val pusher = new M[SupplyChainState]("Pusher has delivered some unit")
    val KeithsFix = new E("Keith is smoking having obtained tobacco and matches and rolled a cigarette")
    val SlashsFix = new E("Slash is smoking having obtained tobacco and paper and rolled a cigarette")
    val JimisFix = new E("Jimi is smoking having obtained matches and paper and rolled a cigarette")

    val KeithInNeed = new E("Keith is in need of tobacco and matches")
    val SlashInNeed = new E("Slash is in need of tobacco and paper")
    val JimiInNeed = new E("Jimi is in need of matches and paper")

    val tobaccoShipment = new M[SupplyChainState]("tobacco shipment")
    val matchesShipment = new M[SupplyChainState]("matches shipment")
    val paperShipment = new M[SupplyChainState]("paper shipment")

    val pusherDone = new E("done")
    val check = new EE("check") // blocking Unit, only blocking molecule of the example.

    val logFile = new ConcurrentLinkedQueue[String]

    site(tp) (
      go { case pusher(SupplyChainState(n, ShippedInventory(t, p, m))) =>
        logFile.add(s"$n,$t,$p,$m")
        scala.util.Random.nextInt(3) match { // select the 2 ingredients randomly
          case 0 =>
            val s = SupplyChainState(n-1, ShippedInventory(t+1, p, m+1))
            tobaccoShipment(s)
            matchesShipment(s)
          case 1 =>
            val s = SupplyChainState(n-1, ShippedInventory(t+1, p+1, m))
            tobaccoShipment(s)
            paperShipment(s)
          case _ =>
            val s = SupplyChainState(n-1, ShippedInventory(t, p+1, m+1))
            matchesShipment(s)
            paperShipment(s)
        }
        if (n == 1) pusherDone()
      },
      go { case KeithsFix(_) => KeithInNeed() },
      go { case SlashsFix(_) => SlashInNeed() },
      go { case JimisFix(_) => JimiInNeed() },
      go { case pusherDone(_) + check(_, r) => r() },

      go { case KeithInNeed(_) + tobaccoShipment(s) + matchesShipment(_) =>
        KeithsFix(); smoke(); pusher(s)
      },
      go { case SlashInNeed(_) + tobaccoShipment(s) + paperShipment(_) =>
        SlashsFix(); smoke(); pusher(s)
      },
      go { case JimiInNeed(_) + matchesShipment(s) + paperShipment(_) =>
        JimisFix(); smoke(); pusher(s)
      }
    )

    KeithInNeed(()) + SlashInNeed(()) + JimiInNeed(())
    pusher(SupplyChainState(supplyLineSize, ShippedInventory(0,0,0)))
    check()
    val result = logFile.iterator().asScala.toSeq
    (0 until supplyLineSize).foreach { i =>
        val current: Array[String] = result(i).split(',')
        List(current(1).toInt, current(2).toInt, current(3).toInt).sum shouldEqual(2*i) // # ingredients handed out at each cycle is twice number of cycles
        current(0).toInt + i shouldEqual supplyLineSize // # cycles outstanding + cycles ran should be 10.
    }
  }
}
