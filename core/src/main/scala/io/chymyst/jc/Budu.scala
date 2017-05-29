package io.chymyst.jc

import scala.annotation.tailrec
import scala.concurrent.duration.Duration

class Budu[X] {
  @volatile var result: X = _
  @volatile var haveResult: Boolean = false
  @volatile var timedOut: Boolean = false

  @tailrec
  private def waitUntil(targetTime: Long, newDuration: Long): Unit = {
    if (newDuration > 0) {
      wait(newDuration)
      if (!haveResult) {
        waitUntil(targetTime, targetTime - System.currentTimeMillis())
      }
    }
  }

  def await(duration: Duration): Option[X] = if (timedOut || haveResult) Option(result) else {
    var newDuration = duration.toMillis
    val targetTime = newDuration + System.currentTimeMillis()
    synchronized {
      if (!haveResult) waitUntil(targetTime, newDuration)
      timedOut = !haveResult
      if (haveResult) Some(result) else None
    }
  }

  def get: X = if (timedOut || haveResult) result else {
    synchronized {
      while (!haveResult) {
        wait()
      }
      result
    }
  }

  def is(x: X): Unit = if (timedOut || haveResult) () else {
    synchronized {
      if (!timedOut) {
        result = x
        haveResult = true
        notify()
      }
    }
  }

  def isAwaited(x: X): Boolean = if (timedOut || haveResult) !timedOut else {
    synchronized {
      if (!timedOut) {
        result = x
        haveResult = true
        notify()
      }
      !timedOut
    }
  }
}

object Budu {
  def apply[X]: Budu[X] = new Budu[X]()
}
