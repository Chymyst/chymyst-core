package io.chymyst.test

import java.util.concurrent.ConcurrentLinkedQueue

import io.chymyst.jc._
import org.scalatest.{BeforeAndAfterEach, Matchers}

import scala.collection.JavaConverters.asScalaIteratorConverter

class Patterns02Spec extends LogSpec with Matchers with BeforeAndAfterEach {

  var tp: Pool = _

  override def beforeEach(): Unit = {
    tp = new BlockingPool(6)
  }

  override def afterEach(): Unit = {
    tp.shutdownNow()
  }

  behavior of "Readers/Writers"

  sealed trait RWLogMessage

  case object ReadingStarted extends RWLogMessage

  case class ReadingEnded(x: Int) extends RWLogMessage

  case class WritingStarted(x: Int) extends RWLogMessage

  case object WritingEnded extends RWLogMessage

  def checkLog(log: ConcurrentLinkedQueue[RWLogMessage], n: Int): (Int, Int, Int, Boolean, Boolean, IndexedSeq[String]) =
    log.iterator.asScala.foldLeft[(Int, Int, Int, Boolean, Boolean, IndexedSeq[String])]((0, 0, 0, false, false, IndexedSeq())) { (acc, message) =>
      val (readers, writers, crossovers, previousWasWriter, previousWasReader, errors) = acc
      message match {
        case ReadingStarted => (readers + 1, writers, crossovers + (if (readers == 0 && previousWasWriter) 1 else 0), false, true,
          if (writers > 0)
            errors ++ Seq(s"(r=$readers, w=$writers) reading started while had writers")
          else if (readers < n)
            errors
          else
            errors ++ Seq(s"(r=$readers, w=$writers) reading started while had > $n readers")
        )
        case ReadingEnded(x) => (readers - 1, writers, crossovers, false, true,
          if (writers > 0)
            errors ++ Seq(s"(r=$readers, w=$writers) reading ended while had writers")
          else if (readers - 1 < n)
            errors
          else
            errors ++ Seq(s"(r=$readers, w=$writers) reading ended while had > $n readers")
        )
        case WritingStarted(x) => (readers, writers + 1, crossovers + (if (writers == 0 && previousWasReader) 1 else 0), true, false,
          if (readers > 0)
            errors ++ Seq(s"(r=$readers, w=$writers) writing started while had readers")
          else if (writers < 1)
            errors
          else
            errors ++ Seq(s"(r=$readers, w=$writers) writing started while had >1 writers")
        )
        case WritingEnded => (readers, writers - 1, crossovers, true, false,
          if (readers > 0)
            errors ++ Seq(s"(r=$readers, w=$writers) writing ended while had readers")
          else if (writers - 1 < 1)
            errors
          else
            errors ++ Seq(s"(r=$readers, w=$writers) writing ended while had >1 writers")
        )
      }
    }

  it should "implement nonblocking version" in {
    val log = new ConcurrentLinkedQueue[RWLogMessage]()
    var resource: Int = 0

    def readResource(): Int = {
      log.add(ReadingStarted)
      BlockingIdle(Thread.sleep(100))
      val x = resource
      log.add(ReadingEnded(x))
      x
    }

    def writeResource(x: Int): Unit = {
      log.add(WritingStarted(x))
      BlockingIdle(Thread.sleep(100))
      resource = x
      log.add(WritingEnded)
      ()
    }

    val read = m[Unit]
    val readResult = m[Int]
    val write = m[Int]
    val access = m[Int]
    val finished = m[Unit]

    val all_done = b[Unit, Unit]
    val counter = m[Int]
    val n = 3
    val iter1 = 3
    val iter2 = 12
    val iterations = iter1 * iter2

    site(tp)(
      go { case readResult(_) => },
      go { case read(_) + access(k) if k < n =>
        access(k + 1)
        val x = readResource()
        readResult(x)
        finished()
      },
      go { case write(x) + access(0) => writeResource(x); access(0) },
      go { case finished(_) + access(k) + counter(l) => access(k - 1) + counter(l + 1) },
      go { case counter(i) + all_done(_, r) if i >= iterations => r() + counter(i) },
      go { case _ => access(0) + counter(0) }
    )

    // Emit read() and write() at staggered intervals, allowing some consumption to take place.
    (1 to iter2).foreach { j => (1 to iter1).foreach { i => write(i) + read(); Thread.sleep(20); write(i) + write(i) }; Thread.sleep(200); }

    all_done()

    val (_, _, crossovers, _, _, errors) = checkLog(log, n)
    println(s"Nonblocking Readers/Writers: Changed $crossovers times between reading and writing (iter1=$iter1, iter2=$iter2)")
    crossovers should be > 0
    errors shouldEqual IndexedSeq()
  }

  it should "implement blocking version" in {
    val log = new ConcurrentLinkedQueue[RWLogMessage]()
    var resource: Int = 0

    def readResource(): Int = {
      log.add(ReadingStarted)
      BlockingIdle(Thread.sleep(100))
      val x = resource
      log.add(ReadingEnded(x))
      x
    }

    def writeResource(x: Int): Unit = {
      log.add(WritingStarted(x))
      BlockingIdle(Thread.sleep(100))
      resource = x
      log.add(WritingEnded)
      ()
    }

    val read = b[Unit, Int]
    val write = b[Int, Unit]
    val access = m[Int]
    val finished = m[Unit]
    val n = 3 // can be a run-time parameter

    val all_done = b[Unit, Unit]
    val counter = m[Int]
    val iter1 = 3
    val iter2 = 12
    val iterations = iter1 * iter2

    site(tp)(
      go { case read(_, readReply) + access(k) if k < n =>
        access(k + 1)
        val x = readResource()
        readReply(x)
        finished()
      },
      go { case write(x, writeReply) + access(0) => writeResource(x); writeReply(); access(0) },
      go { case finished(_) + access(k) + counter(l) => access(k - 1) + counter(l + 1) },
      go { case counter(i) + all_done(_, r) if i >= iterations => r() + counter(i) },
      go { case _ => access(0) + counter(0) }
    )

    // Auxiliary reactions will send requests to read and write.
    val toRead = m[Unit]
    val toWrite = m[Int]
    site(tp)(
      go { case toRead(_) => read() },
      go { case toWrite(x) => write(x) }
    )

    // Emit read() and write() at staggered intervals, allowing some consumption to take place.
    (1 to iter2).foreach { j => (1 to iter1).foreach { i => toWrite(i) + toRead(); Thread.sleep(20); toWrite(i) + toWrite(i) }; Thread.sleep(200); }

    all_done()

    val (_, _, crossovers, _, _, errors) = checkLog(log, n)
    println(s"Nonblocking Readers/Writers: Changed $crossovers times between reading and writing (iter1=$iter1, iter2=$iter2)")
    crossovers should be > 0
    errors shouldEqual IndexedSeq()
  }

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
    val Keith = new M[Unit]("Keith obtained tobacco and matches to get his fix")
    val Slash = new M[Unit]("Slash obtained tobacco and matches to get his fix")
    val Jimi = new M[Unit]("Jimi obtained tobacco and matches to get his fix")

    val tobacco = m[ShippedInventory]
    // this is not particularly elegant, ideally this should carry Unit but pusher needs to obtain current state
    val matches = m[ShippedInventory]
    val paper = m[ShippedInventory]

    val check = new B[Unit, Unit]("check") // blocking Unit, only blocking molecule of the example.

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
            tobacco(s)
            matches(s)
          case 1 =>
            val s = ShippedInventory(t + 1, p + 1, m)
            tobacco(s)
            paper(s)
          case _ =>
            val s = ShippedInventory(t, p + 1, m + 1)
            matches(s)
            paper(s)
        }
        count(n - 1)
      },
      go { case count(0) + check(_, r) => r() },

      go { case Keith(_) + tobacco(s) + matches(_) => enjoyAndResume(s); Keith() },
      go { case Slash(_) + tobacco(s) + paper(_) => enjoyAndResume(s); Slash() },
      go { case Jimi(_) + matches(s) + paper(_) => enjoyAndResume(s); Jimi() }
    )

    Keith() + Slash() + Jimi()
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
    val Keith = new M[Unit]("Keith obtained tobacco and matches to get his fix")
    val Slash = new M[Unit]("Slash obtained tobacco and paper to get his fix")
    val Jimi = new M[Unit]("Jimi obtained matches and paper to get his fix")

    val tobacco = m[Unit]
    val matches = m[Unit]
    val paper = m[Unit]

    val check = new B[Unit, Unit]("check") // blocking Unit, only blocking molecule of the example.

    val logFile = new ConcurrentLinkedQueue[String]

    site(tp)(
      go { case pusher(ShippedInventory(t, p, m)) + count(n) if n >= 1 =>
        logFile.add(s"$n,$t,$p,$m")
        var s = ShippedInventory(t, p, m)
        scala.util.Random.nextInt(3) match {
          // select the 2 ingredients randomly
          case 0 =>
            s = ShippedInventory(t + 1, p, m + 1)
            tobacco()
            matches()
          case 1 =>
            s = ShippedInventory(t + 1, p + 1, m)
            tobacco()
            paper()
          case _ =>
            s = ShippedInventory(t, p + 1, m + 1)
            matches()
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

    Keith() + Slash() + Jimi(())
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

  it should "implement dining savages" in {
    val maxPerPot = 7
    // number of consecutive ingredients added to the pot (each ingredient is a prisoner of the tribe and takes time to add in)
    val batches = 10
    // number of times the cook will fill in the pot with all required ingredients
    val supplyLineSize = maxPerPot * batches
    // number of ingredients cook puts in the pot over time of the simulation (excludes initial state with pot full)
    val savages = List("Anita", "Patrick", "Ivan", "Manfred").toIndexedSeq
    // population of savages taking turn in eating from the pot
    val check = b[Unit, Unit] // molecule used to determine end of simulation

    sealed trait StoryEvent {
      def toString: String
    }
    case object CookRetires extends StoryEvent {
      override def toString: String = "cook is done, savages may eat last batch"
    }
    case object CookStartsToWork extends StoryEvent {
      override def toString: String = "cook finds empty pot and gets to work"
    }
    case object EndOfSimulation extends StoryEvent {
      override def toString: String =
        "ending simulation, no more ingredients available, savages will have to fish or eat berries or raid again"
    }
    final case class CookAddsVictim(victimsToBeCooked: Int, batchVictim: Int) extends StoryEvent {
      override def toString: String =
        s"cook finds unfilled pot and gets cooking with $batchVictim-th victim ingredient " +
          s"for current batch with $victimsToBeCooked victims to be cooked"
    }
    final case class CookCompletedBatch(victimsToBeCooked: Int) extends StoryEvent {
      override def toString: String =
        s"cook notices he finished adding all ingredients with $victimsToBeCooked victims to be cooked"
    }
    final case class SavageEating(name: String, batchVictim: Int) extends StoryEvent {
      override def toString: String = s"$name about to eat ingredient # $batchVictim"
    }

    val Cook = m[Int]
    // counts ingredients to be consumed, so after a while decides it's enough.
    val CookHadEnough = m[Unit]
    val busyCookingIngredientsInPot = m[Int]
    val savage = m[String]

    val availableIngredientsInPot = m[Int]

    val userStory = new ConcurrentLinkedQueue[StoryEvent]

    def pauseForIngredient(): Unit = Thread.sleep(math.floor(scala.util.Random.nextDouble * 20.0 + 2.0).toLong)

    def eatSingleServing(name: String, batchVictim: Int): Unit = {
      userStory.add(SavageEating(name, batchVictim))
      Thread.sleep(math.floor(scala.util.Random.nextDouble * 20.0 + 2.0).toLong)
      availableIngredientsInPot(batchVictim - 1) // one fewer serving.
    }

    site(tp)(
      go { case Cook(0) =>
        userStory.add(CookCompletedBatch(0))
        userStory.add(CookAddsVictim(0, 1))
        userStory.add(CookRetires)
        CookHadEnough()
        availableIngredientsInPot(maxPerPot)
      },
      go { case CookHadEnough(_) + availableIngredientsInPot(0) + check(_, r) =>
        userStory.add(EndOfSimulation)
        r()
      },
      go { case Cook(n) + availableIngredientsInPot(0) if n > 0 => // cook gets activated once the pot reaches an empty state.
        userStory.add(CookStartsToWork)
        pauseForIngredient()
        busyCookingIngredientsInPot(1) // switch of counting from availableIngredientsInPot to busyCooking indicates we're refilling the pot.
        Cook(n - 1)
      },

      go { case Cook(m) + busyCookingIngredientsInPot(n) if m > 0 =>
        userStory.add(CookAddsVictim(m, n))
        if (n < maxPerPot) {
          pauseForIngredient()
          busyCookingIngredientsInPot(n + 1)
          Cook(m - 1)
        } else {
          userStory.add(CookCompletedBatch(m))
          availableIngredientsInPot(maxPerPot) // switch of counting from busyCooking to availableIngredientsInPot indicates we're consuming the pot.
          Cook(m)
        }
      },
      go { case savage(name) + availableIngredientsInPot(n) if n > 0 =>
        eatSingleServing(name, n)
        val randomSavageName = savages(scala.util.Random.nextInt(savages.size))
        savage(randomSavageName) // emit random savage molecule
      }
    )
    savages.foreach(savage) // emit a distinct savage molecule for each name using savages as dictionary
    Cook(supplyLineSize) // if running as a daemon, we would not count down for the Cook.
    availableIngredientsInPot(maxPerPot) // this molecule signifies pot is available for savages to eat.
    check()

    // Unit test validation follows
    val result = userStory.iterator().asScala.toSeq
    // result.foreach(println) // to look at it.
    val retireCount = result.collect { case (CookRetires) => 1 }.sum
    val batchStartsCount = result.collect { case (CookStartsToWork) => 1 }.sum
    val simulationCount = result.collect { case (EndOfSimulation) => 1 }.sum
    val victimsCount = result.collect { case (CookAddsVictim(_, _)) => 1 }.sum
    val batchCompletionsCount = result.collect { case (CookCompletedBatch(_)) => 1 }.sum
    val savageEatingCount = result.collect { case (SavageEating(_, _)) => 1 }.sum

    retireCount shouldEqual 1
    simulationCount shouldEqual 1

    batchStartsCount shouldEqual batches
    batchCompletionsCount shouldEqual batches

    victimsCount shouldEqual maxPerPot * batches
    savageEatingCount shouldEqual maxPerPot * (1 + batches) // our initial condition starts with a full pot.
  }

}