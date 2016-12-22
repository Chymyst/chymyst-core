package code.chymyst.benchmark

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
    val prime1 = timeThis {
      task
    }
    val prime2 = timeThis {
      task
    }
    val result = timeThis {
      task
    }
    //    println(s"timing with priming: prime1 = $prime1, prime2 = $prime2, result = $result")
    (result + prime2 + 1) / 2
  }

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

}
