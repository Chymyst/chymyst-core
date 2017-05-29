package io.chymyst.jc

import Core.AnyOpsEquals
import scala.concurrent.duration.Duration

class Budu[X] {
  @volatile var result: X = _
  @volatile var haveResult: Boolean = false
  @volatile var timedOut: Boolean = false

  def await(duration: Duration): Option[X] = {
    synchronized {
      // TODO add a while loop with explicit timeout check - look up Doug Lea's java code
      wait(duration.toMillis)
      timedOut = !haveResult
      if (haveResult) Some(result) else None
    }
  }

  def get: X = {
    synchronized{
      while(!haveResult) {
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
