package io.chymyst.test

import io.chymyst.jc.Budu

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

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

}
