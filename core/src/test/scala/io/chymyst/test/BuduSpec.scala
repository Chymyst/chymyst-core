package io.chymyst.test

import io.chymyst.jc.Budu

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.{Duration, DurationInt}
import io.chymyst.test.Common._

class BuduSpec extends LogSpec {
  behavior of "Budu()"

  it should "wait for reply" in {
    val x = Budu[Int]
    Future {
      x.is(123)
    }
    x.get shouldEqual 123
  }

  it should "wait for reply and report status" in {
    val x = Budu[Int]
    val y = Budu[Boolean]
    Future {
      y.is(x.isAwaited(123))
    }
    x.get shouldEqual 123
    y.get shouldEqual true
  }

  it should "wait for reply with time-out not reached" in {
    val x = Budu[Int]
    Future {
      Thread.sleep(100)
      x.is(123)
    }
    x.await(500.millis) shouldEqual Some(123)
  }

  it should "wait for reply with time-out reached" in {
    val x = Budu[Int]
    Future {
      Thread.sleep(500)
      x.is(123)
    }
    x.await(100.millis) shouldEqual None
  }

  it should "wait for reply with time-out not reached and report status" in {
    val x = Budu[Int]
    val y = Budu[Boolean]
    Future {
      Thread.sleep(100)
      y.is(x.isAwaited(123))
    }
    x.await(500.millis) shouldEqual Some(123)
    y.get shouldEqual true
  }

  it should "wait for reply with time-out reached and report status" in {
    val x = Budu[Int]
    val y = Budu[Boolean]
    Future {
      Thread.sleep(500)
      y.is(x.isAwaited(123))
    }
    x.await(100.millis) shouldEqual None
    y.get shouldEqual false
  }

  behavior of "performance benchmark"

  val total = 2000
  val best = 20

  it should "measure reply speed for Budu" in {
    val results = (1 to total).map { _ ⇒
      val x = Budu[Long]
      Future {
        x.is(System.nanoTime())
      }
      val now = x.get
      System.nanoTime - now
    }.sortBy(-_).drop(total - best).map(_.toDouble)
    val (average, stdev) = meanAndStdev(results)
    println(s"Best amortized reply speed for Budu, based on $best best samples: ${formatNanosToMicros(average)} ± ${formatNanosToMicros(stdev)}")
  }

  it should "measure reply speed for Promise" in {
    val results = (1 to total).map { _ ⇒
      val x = Promise[Long]
      Future {
        x.success(System.nanoTime())
      }
      val now = Await.result(x.future, Duration.Inf)
      System.nanoTime - now
    }.sortBy(-_).drop(total - best).map(_.toDouble)
    val (average, stdev) = meanAndStdev(results)
    println(s"Best amortized reply speed for Promise, based on $best best samples: ${formatNanosToMicros(average)} ± ${formatNanosToMicros(stdev)}")
  }

  it should "measure time-out reply speed for Budu" in {
    val results = (1 to total).map { _ ⇒
      val x = Budu[Long]
      Future {
        x.is(System.nanoTime())
      }
      val now = x.await(10.seconds).get
      System.nanoTime - now
    }.sortBy(-_).drop(total - best).map(_.toDouble)
    val (average, stdev) = meanAndStdev(results)
    println(s"Best amortized time-out reply speed for Budu, based on $best best samples: ${formatNanosToMicros(average)} ± ${formatNanosToMicros(stdev)}")
  }

  it should "measure time-out reply speed for Promise" in {
    val results = (1 to total).map { _ ⇒
      val x = Promise[Long]
      Future {
        x.success(System.nanoTime())
      }
      val now = Await.result(x.future, 10.seconds)
      System.nanoTime - now
    }.sortBy(-_).drop(total - best).map(_.toDouble)
    val (average, stdev) = meanAndStdev(results)
    println(s"Best amortized time-out reply speed for Promise, based on $best best samples: ${formatNanosToMicros(average)} ± ${formatNanosToMicros(stdev)}")
  }

}
