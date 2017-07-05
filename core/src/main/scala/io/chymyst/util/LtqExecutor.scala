package io.chymyst.util

import java.util
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.{Callable, ExecutorService, Future, LinkedBlockingQueue, LinkedTransferQueue, TimeUnit}

/* See http://fasterjava.blogspot.co.uk/2014/09/writing-non-blocking-executor.html */

final class LtqExecutor[T](val threadCount: Int) extends ExecutorService {
  val queue = new LinkedTransferQueue[Runnable]()
  val threads = Array.tabulate[Thread](threadCount){ _ =>
    val thread = new Thread(new Worker)
    thread.start()
    thread
  }
  private var running: Boolean = true
  private var stopped: Boolean = false

  private val MAX_QUEUE_SIZE: Int = 1 << 18

  def execute(runnable: Runnable) {
    while (queue.size > MAX_QUEUE_SIZE) {
      {
        // cap the size
          LockSupport.parkNanos(1)
      }
    }
   /* while (!queue.offer(runnable)) {
      {
        LockSupport.parkNanos(1)
        /* try {
            Thread.sleep(1);
        } catch (InterruptedException ie) {
            throw new RuntimeException(ie);
        } */
      }
    }*/
    try queue.put(runnable)
    catch {
      case e: InterruptedException ⇒ throw new RuntimeException(e)
    }
    /* try {
        queue.put(runnable);
    } catch (InterruptedException ie) {
    throw new RuntimeException(ie);
    } */
  }
/*
  private class Worker extends Runnable {
    def run() {
      while (running) {
        {
          var runnable: Runnable = null
          while (runnable == null) {
            {
              if (Thread.interrupted) return
              runnable = queue.poll()
              if (runnable == null)
                LockSupport.parkNanos(1)
            }
          }
          /* try {
              runnable = queue.take();
          } catch (InterruptedException ie) {
              // was interrupted - just go round the loop again,
              // if running = false will cause it to exit
          } */ try {
          if (runnable != null) {
            runnable.run()
          }
        }
        catch {
          case e: Exception => {
            System.out.println("failed because of: " + e.toString)
            e.printStackTrace()
          }
        }
        }
      }
    }
  }
*/
  def shutdown() {
    running = false
    var i: Int = 0
    while (i < threadCount) {
      {
        threads(i).interrupt()
        threads(i) = null
      }
      {
        i += 1; i - 1
      }
    }
    stopped = true
  }

  def isShutdown: Boolean = {
    running
  }

  def isTerminated: Boolean = {
    stopped
  }

  // ***************************************************
  def awaitTermination(timeout: Long, unit: Nothing): Boolean = {
    throw new UnsupportedOperationException("oops!")
  }

  def invokeAll[T](tasks: Nothing): Nothing = {
    throw new UnsupportedOperationException("oops!")
  }

  def invokeAll[T](tasks: Nothing, timeout: Long, unit: Nothing): Nothing = {
    throw new UnsupportedOperationException("oops!")
  }

  def invokeAny[T](tasks: Nothing): T = {
    throw new UnsupportedOperationException("oops!")
  }

  def invokeAny[T](tasks: Nothing, timeout: Long, unit: Nothing): T = {
    throw new UnsupportedOperationException("oops!")
  }

  def shutdownNow: Nothing = {
    throw new UnsupportedOperationException("oops!")
  }

  def submit[T](task: Nothing): Nothing = {
    throw new UnsupportedOperationException("oops!")
  }

  def submit(task: Runnable): Nothing = {
    throw new UnsupportedOperationException("oops!")
  }

  def submit[T](task: Runnable, result: T): Nothing = {
    throw new UnsupportedOperationException("oops!")
  }

  override def toString: String = {
    throw new UnsupportedOperationException("oops!")
  }

  override def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = ???

  override def invokeAll[T](tasks: util.Collection[_ <: Callable[T]]): util.List[Future[T]] = ???

  override def invokeAll[T](tasks: util.Collection[_ <: Callable[T]], timeout: Long, unit: TimeUnit): util.List[Future[T]] = ???

  override def invokeAny[T](tasks: util.Collection[_ <: Callable[T]]): T = ???

  override def invokeAny[T](tasks: util.Collection[_ <: Callable[T]], timeout: Long, unit: TimeUnit): T = ???

  override def submit[T](task: Callable[T]): Future[T] = ???


  private class Worker extends Runnable {
    def run(): Unit = {
      while (running) {
        var runnable: Runnable = null
        try {
          runnable = queue.take()
        } catch {
          case e: InterruptedException ⇒
          // was interrupted - just go round the loop again,
          // if running = false will cause it to exit
        }
        try {
          if (runnable != null) {
            runnable.run()
          }
        } catch {
          case e: Exception ⇒
            System.out.println("failed because of: " + e.toString())
            e.printStackTrace()
        }
      }
    }
  }


}
