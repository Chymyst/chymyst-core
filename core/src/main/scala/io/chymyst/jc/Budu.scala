package io.chymyst.jc

import io.chymyst.jc.Budu._
import io.chymyst.jc.Core.AnyOpsEquals

import scala.annotation.tailrec
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.Duration

// There are only three states: 2 is empty, no time-out yet. 3 is empty, have time-out. 1 not empty, no time-out.

final class Budu[X](useFuture: Boolean) {
  private val resultPromise: Promise[X] =
    if (useFuture)
      Promise[X]()
    else null.asInstanceOf[Promise[X]]

  @volatile private var result: X = _
  //  @volatile var haveNoReply: Boolean = true
  //  @volatile var notTimedOutYet: Boolean = true
  @volatile private var state: Int = EmptyNoTimeout

  def isEmpty: Boolean = haveNoReply

  def isTimedOut: Boolean = !notTimedOutYet

  @inline private def haveNoReply: Boolean = state > 1

  @inline private def notTimedOutYet: Boolean = state < 3

  @tailrec
  private def waitUntil(targetTime: Long, newDuration: Long): Unit = {
    if (newDuration > 0) {
      wait(newDuration)
      if (haveNoReply) {
        waitUntil(targetTime, targetTime - System.currentTimeMillis())
      }
    }
  }

  def await(duration: Duration): Option[X] =
    if (state === EmptyNoTimeout) {
      val newDuration = duration.toMillis
      val targetTime = newDuration + System.currentTimeMillis()
      synchronized {
        if (haveNoReply)
          waitUntil(targetTime, newDuration)
        if (haveNoReply) {
          state = EmptyAfterTimeout
          None
        } else
          Some(result)
      }
    } else
      Option(result)


  def await: X =
    if (state === EmptyNoTimeout) {
      synchronized {
        while (haveNoReply) {
          wait()
        }
        result
      }
    } else result

  def getFuture: Future[X] = if (useFuture)
    resultPromise.future
  else throw new Exception("getFuture() is disabled, initialize as Budu(useFuture = true) to enable")

  def is(x: X): Boolean =
    if (state === EmptyNoTimeout) {
      synchronized {
        if (notTimedOutYet) {
          result = x
          state = NonEmptyNoTimeout
          notify()
          if (useFuture)
            resultPromise.success(x)
        }
        notTimedOutYet
      }
    } else notTimedOutYet
}

object Budu {
  private final val NonEmptyNoTimeout: Int = 1
  private final val EmptyNoTimeout: Int = 2
  private final val EmptyAfterTimeout: Int = 3

  def apply[X](useFuture: Boolean): Budu[X] = new Budu[X](useFuture)

  def apply[X]: Budu[X] = new Budu[X](useFuture = false)
}
