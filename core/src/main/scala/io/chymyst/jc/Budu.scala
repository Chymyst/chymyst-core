package io.chymyst.jc

import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import Core.AnyOpsEquals

// There are only three states: 2 is empty, no time-out yet. 3 is empty, have time-out. 1 not empty, no time-out.

class Budu[X] {
  @volatile var result: X = _
  //  @volatile var isEmpty: Boolean = true
  //  @volatile var notTimedOutYet: Boolean = true
  @volatile var state: Int = 2

  @inline def isEmpty: Boolean = state > 1

  @inline def notTimedOutYet: Boolean = state < 3

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
    if (state === 2) {
      var newDuration = duration.toMillis
      val targetTime = newDuration + System.currentTimeMillis()
      synchronized {
        if (isEmpty)
          waitUntil(targetTime, newDuration)
        if (isEmpty) {
          state = 3
          None
        } else
          Some(result)
      }
    } else
      Option(result)


  def get: X =
    if (state === 2) {
      synchronized {
        while (isEmpty) {
          wait()
        }
        result
      }
    } else result

  def is(x: X): Unit =
    if (state === 2) {
      synchronized {
        if (notTimedOutYet) {
          result = x
          state = 1
          notify()
        }
      }
    } else ()

  def isAwaited(x: X): Boolean =
    if (state === 2) {
      synchronized {
        if (notTimedOutYet) {
          result = x
          state = 1
          notify()
        }
        notTimedOutYet
      }
    } else notTimedOutYet
}

object Budu {
  def apply[X]: Budu[X] = new Budu[X]()
}
