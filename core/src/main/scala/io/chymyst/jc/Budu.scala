package io.chymyst.jc

import scala.annotation.tailrec
import scala.concurrent.duration.Duration

// There are only three states: 3. No result yet, no time-out yet. 2. Have result, no time-out. 1. Have time-out, no result.

class Budu[X] {
  @volatile var result: X = _
  @volatile var isEmpty: Boolean = true
  @volatile var notTimedOutYet: Boolean = true

  @tailrec
  private def waitUntil(targetTime: Long, newDuration: Long): Unit = {
    if (newDuration > 0) {
      wait(newDuration)
      if (isEmpty) {
        waitUntil(targetTime, targetTime - System.currentTimeMillis())
      }
    }
  }

  def await(duration: Duration): Option[X] =
    if (notTimedOutYet && isEmpty) {
    var newDuration = duration.toMillis
    val targetTime = newDuration + System.currentTimeMillis()
    synchronized {
      if (isEmpty)
        waitUntil(targetTime, newDuration)
      notTimedOutYet = !isEmpty
      if (isEmpty)
        None
      else
        Some(result)
    }
  } else
      Option(result)


  def get: X =
    if (notTimedOutYet && isEmpty) {
    synchronized {
      while (isEmpty) {
        wait()
      }
      result
    }
  } else result

  def is(x: X): Unit =
    if (notTimedOutYet && isEmpty) {
    synchronized {
      if (notTimedOutYet) {
        result = x
        isEmpty = false
        notify()
      }
    }
  } else ()

  def isAwaited(x: X): Boolean =
    if (notTimedOutYet && isEmpty) {
    synchronized {
      if (notTimedOutYet) {
        result = x
        isEmpty = false
        notify()
      }
      notTimedOutYet
    }
  } else notTimedOutYet
}

object Budu {
  def apply[X]: Budu[X] = new Budu[X]()
}
