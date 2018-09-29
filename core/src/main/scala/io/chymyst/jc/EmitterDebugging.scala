package io.chymyst.jc

import scala.concurrent.{Future, Promise}

/** Methods used for debugging a non-blocking molecule emitter.
  * 
  * @tparam T The value type of the molecule.
  */
trait EmitterDebugging[T] { self: MolEmitter ⇒

  /** Define the next emission event. The resulting `Future` will resolve once, at the next time this molecule is emitted.
    *
    * @return `Future[T]` holding the value of type `T` that will be carried by the emitted molecule.
    */
  def whenEmitted: Future[T] = whenEmittedFuture.asInstanceOf[Future[T]]

  /** Emit a molecule with value `v`, and define the corresponding consumption event.
    * The resulting `Future` will resolve once, when some reaction consumes the molecule value just emitted now.
    *
    * @param v Value of the molecule, to be emitted now.
    * @return `Future[T]` holding the value of type `T` that is consumed by reaction.
    */
  def emitUntilConsumed(v: T): Future[T] =
    if (isChymystThread)
      Promise[T]().failure(exceptionDisallowedWhenConsumed).future
    else ensureReactionSite {
      if (isStatic)
        throw new ExceptionEmittingStaticMol(s"Error: static molecule $this($v) cannot be emitted non-statically")
      else {
        val mv = MolValue(v)
        val fut = mv.whenConsumed
        reactionSite.emit(this, mv)
        fut
      }
    }

  def emitUntilConsumed()(implicit arg: TypeMustBeUnit[T]): Future[T] = (emitUntilConsumed(arg.getUnit): @inline)

  private val exceptionDisallowedWhenConsumed = new Exception(s"emitUntilConsumed() is disallowed on reaction threads (molecule: $this)")

  /** Define the scheduler decision event for reactions consuming this molecule.
    * The resulting `Future` will resolve successfully when some reaction could be found that consumes some copy of this molecule,
    * and will fail if no reaction consuming this molecule can start at the next time scheduling decisions are made.
    *
    * Note that the scheduler may be looking for reactions consuming another emitted molecule and, as a result, schedule a
    * reaction consuming a copy of `this` molecule.
    * In this case, the returned `Future` will also resolve successfully.
    *
    * The resolved `String` value of the `Future` shows the name of the molecule for which the scheduler decision was made.
    * This does not necessarily coincide with the molecule on which `whenScheduled()` is called.
    *
    * @return `Future[String]` that either succeeds, with the name of the molecule, or fails.
    */
  def whenScheduled: Future[String] =
    if (isChymystThread)
      Promise[String]().failure(exceptionDisallowedwhenScheduled).future
    else whenScheduledFuture

  private val exceptionDisallowedwhenScheduled = new Exception(s"whenScheduled() is disallowed on reaction threads (molecule: $this)")

}
