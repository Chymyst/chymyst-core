package io.chymyst.test

import io.chymyst.jc._

import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, Promise}
import scala.util.{Failure, Success, Try}

class HappyEyeballsSpec extends LogSpec {

  behavior of "happy eyeballs algorithm"

  // A helper function: Make a molecule cancellable.
  // Given a molecule x: M[A], create another molecule cx: M[A] and a "cancel" molecule c: M[Unit].
  // Emitting cx(a) will lead to emitting x(a) until c() has been emitted. After that, cx(a) can be emitted at will but has no effect.
  // Emitting more than one c() has the same effect as emitting just one c().
  def cancellable[A](x: M[A]): (M[A], M[Unit]) = {
    val cx = m[A]
    val cancel = m[Unit]
    val enable = m[Unit]
    site(
      go { case cx(a) + enable(_) ⇒ x(a); enable() },
      go { case enable(_) + cancel(_) ⇒ }
    )
    enable()
    (cx, cancel)
  }

  def emitAfterDelay[A](x: M[A], delay: Duration): M[A] = {
    val dx = m[A]
    site(
      go { case dx(a) ⇒ BlockingIdle(Thread.sleep(delay.toMillis)); x(a) }
    )
    dx
  }

  // Given a function f: T => R and a List[T], call f on each element of the list with increasing delays until one of them finishes.
  // Stop running and/or ignore results of functions that finish later. Return the first received result of type R.
  // If one of the functions throws an exception early, start another one immediately (without waiting for the rest of the delay).

  // Users of the HEB library need to supply a bound molecule of type M[Try[R]] for reporting the final result.
  // To start the algorithm, users will emit a new molecule holding a value of type List[T]. The HEB library provides that molecule.

  // The final result will be a failure if every function call failed.

  // Reasoning: the computation must be data-driven. The input data can be put on molecules such as data: M[T].
  // However, this data will not be processed all at once. To prevent a reaction from starting, we need to withhold some input molecules.
  // Use a token molecule; the reaction will not run until a token is given. Initially, one token is given and a timeout is started at the same time.
  // When the timeout elapses and no successful result is returned so far, another token will be given.
  // When a failure is returned, the timeout is canceled (if it has not yet elapsed) and another token is given immediately.
  // When a success molecule is returned, all timeouts and tokens are canceled, and the result is reported immediately.

  // We need a timeout molecule that will be cancellable.
  // We also need to keep track of how many failures we have registered so far. If all computations fail, we need to report the failures.

  def makeHEB[T, R](report: M[Either[List[Throwable], R]], delay: Duration)(slowComputeOrThrow: T ⇒ R): M[List[T]] = {
    val start = m[List[T]]
    val data = m[T] // Initial data for the slow computations.
    val token = m[M[Unit]]
    val timeoutElapsed = m[Unit]

    def delayedTimeoutElapsed = {
      val (cancellableTimeout, cancelTimeout) = cancellable(timeoutElapsed)
      (emitAfterDelay(cancellableTimeout, delay), cancelTimeout)
    }

    val result = m[(M[Unit], Try[R])] // Results of slow computations.
    val finalResult = m[(Int, List[Throwable])] // Final result accumulator.

    // When user emits start(), a sequence of data values is supplied.
    site(
      go { case start(inputs) ⇒
        if (inputs.isEmpty)
          report(Left(List(new Exception("Cannot run HEB with empty input sequence"))))
        else {
          finalResult((inputs.length, List(new Exception("no result computed yet"))))
          inputs.foreach(data(_))
          val (delayed, cancel) = delayedTimeoutElapsed
          token(cancel)
          delayed()
        }
      },
      go { case timeoutElapsed(_) ⇒
        val (delayed, cancel) = delayedTimeoutElapsed
        token(cancel)
        delayed()
      },
      go { case data(t) + token(cancel) ⇒ result((cancel, Try(slowComputeOrThrow(t)))) },
      go { case result((cancel, Failure(e))) + finalResult((n, errors)) ⇒
        cancel()
        if (n == 1) {
          report(Left(e :: errors))
          // TODO: cancel all other molecules
        } else {
          finalResult((n - 1, e :: errors))
        }
      },
      go { case result((cancel, Success(r))) + finalResult((_, _)) ⇒ cancel(); report(Right(r)) }
    )

    start
  }

  it should "run HEB with some delayed computations" in {

    val testSuccess = Promise[Int]()

    // Our reaction that consumes the result of the HEB algorithm. The type of the result is Int.
    val report = m[Either[List[Throwable], Int]]
    site(
      go { case report(Left(errors)) ⇒ println(s"Failed with errors: $errors"); testSuccess.failure(errors.head) },
      go { case report(Right(r)) ⇒ println(s"Succeeded with result: $r"); testSuccess.success(r) }
    )

    val start = makeHEB(report, 2500.millis) { x: Int ⇒ Thread.sleep(x * 1000L); x }

    val inputs = List(3, 4, 1, 5)
    start(inputs)
    Await.result(testSuccess.future, (inputs.sum + 1).seconds) shouldEqual 1
  }
}
