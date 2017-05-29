package io.chymyst.jc

import scala.concurrent.duration.Duration

class Budu[X] {
  @volatile var result: X = _
  @volatile var haveResult: Boolean = false
  @volatile var timedOut: Boolean = false

  def await(duration: Duration): Option[X] = {
    var newDuration = duration.toMillis
    val targetTime = newDuration + System.currentTimeMillis()
    synchronized {
      while (!haveResult && newDuration > 0) {
        wait(newDuration)
        newDuration = targetTime - System.currentTimeMillis()
      }
      timedOut = !haveResult
      if (haveResult) Some(result) else None
    }
  }

  def get: X = {
    synchronized {
      while (!haveResult) {
        wait()
      }
      result
    }
  }

  def is(x: X): Unit = {
    synchronized {
      if (!timedOut) {
        result = x
        haveResult = true
        notify()
      }
    }
  }

  def isAwaited(x: X): Boolean = {
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
