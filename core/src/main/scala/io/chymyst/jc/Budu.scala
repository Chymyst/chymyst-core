package io.chymyst.jc

import io.chymyst.jc.Budu._
import io.chymyst.jc.Core.AnyOpsEquals

import scala.annotation.tailrec
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.Duration

// There are only three states: 2 is empty, no time-out yet. 3 is empty, have time-out. 1 not empty, no time-out.

final class Budu[X] {
  private var resultPromise: Promise[X] = Promise()

  @volatile var result: X = _
  //  @volatile var isEmpty: Boolean = true
  //  @volatile var notTimedOutYet: Boolean = true
  @volatile var state: Int = EmptyNoTimeout

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
    if (state === EmptyNoTimeout) {
      val newDuration = duration.toMillis
      val targetTime = newDuration + System.currentTimeMillis()
      synchronized {
        if (isEmpty)
          waitUntil(targetTime, newDuration)
        if (isEmpty) {
          state = EmptyAfterTimeout
          None
        } else
          Some(result)
      }
    } else
      Option(result)


  def get: X =
    if (state === EmptyNoTimeout) {
      synchronized {
        while (isEmpty) {
          wait()
        }
        result
      }
    } else result

  def getFuture: Future[X] = resultPromise.future

  def is(x: X): Boolean =
    if (state === EmptyNoTimeout) {
      synchronized {
        if (notTimedOutYet) {
          result = x
          resultPromise.success(x)
          state = NonEmptyNoTimeout
          notify()
        }
        notTimedOutYet
      }
    } else notTimedOutYet
}

object Budu {
  private final val NonEmptyNoTimeout: Int = 1
  private final val EmptyNoTimeout: Int = 2
  private final val EmptyAfterTimeout: Int = 3

  def apply[X]: Budu[X] = new Budu[X]()
}
