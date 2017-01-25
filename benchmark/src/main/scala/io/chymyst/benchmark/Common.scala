package io.chymyst.benchmark

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

object Common {
  val warmupTimeMs = 50L

  def elapsed(initTime: LocalDateTime): Long = initTime.until(LocalDateTime.now, ChronoUnit.MILLIS)

  def timeThis(task: => Unit): Long = {
    val initTime = LocalDateTime.now
    task
    elapsed(initTime)
  }

  def timeWithPriming(task: => Unit): Long = {
    task // this is just priming, no measurement

    val result1 = timeThis {
      task
    }
    val result2 = timeThis {
      task
    }
    (result1 + result2 + 1) / 2
  }

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

}
