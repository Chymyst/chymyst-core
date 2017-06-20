package io.chymyst.util

import io.chymyst.jc.Core.AnyOpsEquals
import io.chymyst.util.Budu._

import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise}

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

  @inline def isEmpty: Boolean = haveNoReply

  @inline def isTimedOut: Boolean = !notTimedOutYet

  @inline private def haveNoReply: Boolean = state > 1

  @inline private def notTimedOutYet: Boolean = state < 3

  /** Wait until the thread is notified and we get a reply value.
    * Do not wait any longer than until the given target time.
    * This function needs to be called inside a `synchronized` block.
    *
    * @param targetTime  The absolute time (in milliseconds) until which we need to wait.
    * @param newDuration The first duration of waiting, needs to be precomputed and supplied as argument.
    */
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
          waitUntil(targetTime, newDuration) // This is an additional check that we have no reply. It's hard to trigger a race condition when `haveNoReply` would be false here. So, coverage cannot be 100%.
        // At this point, we have been notified.
        // If we are here, it means that we are holding a `synchronized` monitor, and thus the notifying thread is not holding it any more.
        // Therefore, the notifying thread has either finished its work and supplied us with a reply value, or it did not yet start replying.
        // Checking `haveNoReply` at this point will reveal which of these two possibilities is the case.
        if (haveNoReply) {
          state = EmptyAfterTimeout
          None
        } else
          Some(result)
      } // End of `synchronized`
    } else
      Option(result)


  def await: X = {
    if (state === EmptyNoTimeout) {
      synchronized {
        while (haveNoReply) {
          wait()
        }
      } // End of `synchronized`
    }
    result
  }

  def getFuture: Future[X] = if (useFuture)
    resultPromise.future
  else throw new Exception("getFuture() is disabled, initialize as Budu(useFuture = true) to enable")

  def is(x: X): Boolean =
    if (state === EmptyNoTimeout) {
      synchronized {
        if (notTimedOutYet) {
          result = x
          state = NonEmptyNoTimeout
          // If we are here, we are holding the `synchronized` monitor, which means that the waiting thread is suspended.
          notify()
          // At this point, we are still holding the `synchronized` monitor, and the waiting thread is still suspended.
          // Therefore, it is safe to read or modify the state.
          if (useFuture)
            resultPromise.success(x)
        }
        notTimedOutYet
      } // End of `synchronized`
      // It is only here, after we release the `synchronized` monitor, that the waiting thread is woken up and resumes computation within its `synchronized` block after `wait()`.
    } else notTimedOutYet
}

object Budu {
  private final val NonEmptyNoTimeout: Int = 1
  private final val EmptyNoTimeout: Int = 2
  private final val EmptyAfterTimeout: Int = 3

  def apply[X](useFuture: Boolean): Budu[X] = new Budu[X](useFuture)

  def apply[X]: Budu[X] = new Budu[X](useFuture = false)
}
