package io.chymyst.util

import io.chymyst.test.Common._
import io.chymyst.test.LogSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, Future, Promise}

class BuduSpec extends LogSpec {
  behavior of "Budu()"

  it should "report status = true if no waiting occurred" in {
    val x = Budu[Int]
    x.isEmpty shouldEqual true
    x.isTimedOut shouldEqual false
    x.is(123) shouldEqual true
    x.isEmpty shouldEqual false
    x.isTimedOut shouldEqual false
  }

  it should "wait for reply" in {
    val x = Budu[Int]
    Future {
      x.is(123)
    }
    x.await shouldEqual 123
  }

  it should "return old result when already have reply" in {
    val x = Budu[Int]
    Future {
      x.is(123)
      x.is(200)
    }
    x.await shouldEqual 123
    x.await shouldEqual 123
    x.is(300)
    x.await shouldEqual 123
  }

  it should "wait for reply using Future" in {
    val x = Budu[Int](useFuture = true)
    Future {
      x.is(123)
    }
    Await.result(x.getFuture, Duration.Inf) shouldEqual 123
  }

  it should "produce error when using getFuture without correct initialization" in {
    val x = Budu[Int]
    the[Exception] thrownBy x.getFuture should have message "getFuture() is disabled, initialize as Budu(useFuture = true) to enable"
  }

  it should "wait for reply and report status" in {
    val x = Budu[Int]
    val y = Budu[Boolean]
    x.isEmpty shouldEqual true
    Future {
      y.is(x.is(123))
    }
    x.isTimedOut shouldEqual false
    x.await shouldEqual 123
    x.isEmpty shouldEqual false
    x.isTimedOut shouldEqual false
    y.await shouldEqual true
    x.is(200) shouldEqual true // repeated reply is silently ignored
    x.isEmpty shouldEqual false
    x.isTimedOut shouldEqual false
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
    x.is(200) shouldEqual false
    x.await(100.millis) shouldEqual None
  }

  it should "wait for reply with time-out not reached and report status" in {
    val x = Budu[Int]
    val y = Budu[Boolean]
    Future {
      Thread.sleep(100)
      y.is(x.is(123))
    }
    x.await(500.millis) shouldEqual Some(123)
    y.await shouldEqual true
  }

  it should "wait for reply with time-out reached and report status" in {
    val x = Budu[Int]
    val y = Budu[Boolean]
    Future {
      Thread.sleep(500)
      y.is(x.is(123))
    }
    x.await(100.millis) shouldEqual None
    y.await shouldEqual false
  }

  behavior of "performance benchmark"

  val total = 20000
  val best = 100

  def getBest(res: Seq[Double]): Seq[Double] = res.sortBy(- _).dropRight(best)

  it should "measure reply speed for Budu" in {
    val results = (1 to total).map { _ ⇒
      val x = Budu[Long]
      Future {
        x.is(System.nanoTime())
      }
      val now = x.await
      System.nanoTime - now
    }.map(_.toDouble)
    val (average, stdev) = meanAndStdev(getBest(results))
    println(s"Best amortized reply speed for Budu, based on $best best samples: ${formatNanosToMicros(average)} ± ${formatNanosToMicros(stdev)}")
    showRegression("reply speed for Budu", results)
  }

  it should "measure reply speed for Promise" in {
    val results = (1 to total).map { _ ⇒
      val x = Promise[Long]
      Future {
        x.success(System.nanoTime())
      }
      val now = Await.result(x.future, Duration.Inf)
      System.nanoTime - now
    }.map(_.toDouble)
    val (average, stdev) = meanAndStdev(getBest(results))
    println(s"Best amortized reply speed for Promise, based on $best best samples: ${formatNanosToMicros(average)} ± ${formatNanosToMicros(stdev)}")
    showRegression("reply speed for Promise", results)
  }

  it should "measure time-out reply speed for Budu" in {
    val results = (1 to total).map { _ ⇒
      val x = Budu[Long]
      Future {
        x.is(System.nanoTime())
      }
      val now = x.await(10.seconds).get
      System.nanoTime - now
    }.map(_.toDouble)
    val (average, stdev) = meanAndStdev(getBest(results))
    println(s"Best amortized time-out reply speed for Budu, based on $best best samples: ${formatNanosToMicros(average)} ± ${formatNanosToMicros(stdev)}")
    showRegression("time-out reply speed for Budu", results)
  }

  it should "measure time-out reply speed for Promise" in {
    val results = (1 to total).map { _ ⇒
      val x = Promise[Long]
      Future {
        x.success(System.nanoTime())
      }
      val now = Await.result(x.future, 10.seconds)
      System.nanoTime - now
    }.map(_.toDouble)
    val (average, stdev) = meanAndStdev(getBest(results))
    println(s"Best amortized time-out reply speed for Promise, based on $best best samples: ${formatNanosToMicros(average)} ± ${formatNanosToMicros(stdev)}")
    showRegression("time-out reply speed for Promise", results)
  }

}
