package io.chymyst.jc

import Core._
import java.util.concurrent.{Semaphore, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import scala.collection.mutable
import scala.concurrent.duration.Duration

/** Convenience syntax: provides an `unapply` operation, so that users can write the chemical notation, such as
  * `a(x) + b(y) + ...`, in reaction input patterns.
  * Pattern-matching can be used on molecule values at will, for example:
  * {{{go { case a(MyCaseClass(x, y)) + b(Some(z)) if x > z => ... } }}}
  *
  * The chemical notation should be used only with the left-associative `+` operator grouped to the left.
  * Input patterns with a right-associative grouping of the `+` operator, for example `a(x) + ( b(y) + c(z) )`, are rejected at compile time.
  */
object + {
  def unapply(inputs: ReactionBodyInput): Option[(ReactionBodyInput, ReactionBodyInput)] = {
    val (index, inputMoleculeList) = inputs
    Some(((index - 1, inputMoleculeList), (index, inputMoleculeList)))
  }
}

/** Abstract container for molecule values. This is a common wrapper for values of blocking and non-blocking molecules.
  *
  * @tparam T Type of the molecule value.
  */
private[jc] sealed trait AbsMolValue[T] {
  private[jc] def moleculeValue: T

  /** The hash code of an [[AbsMolValue]] should not depend on anything but the wrapped value (of type `T`).
    * However, extending [[PersistentHashCode]] leads to errors!
    * (See the test "correctly store several molecule copies in a MutableQueueBag" in `ReactionSiteSpec.scala`.)
    * Therefore, we override the `hashCode` directly here, and make it a `lazy val`.
    */
  override lazy val hashCode: Int = moleculeValue.hashCode()

  /** String representation of molecule values will omit printing the `Unit` values but print all other types normally.
    *
    * @return String representation of molecule value of type T. Unit values are printed as empty strings.
    */
  override final def toString: String = moleculeValue match {
    case () => ""
    case v => v.toString
  }

  /** Checks whether the reaction has sent no reply to this molecule, and also that there was no error and no timeout with reply.
    * This check is meaningful only for blocking molecules and only after the reaction body has finished evaluating.
    *
    * @return `true` if the reaction has failed to send a reply to this instance of the blocking molecule.
    *         Will also return `false` if this molecule is not a blocking molecule.
    */
  // This method is in the parent trait only because we would like to check for missing replies faster,
  // without pattern-matching on blocking vs non-blocking molecules.
  private[jc] def reactionSentNoReply: Boolean = false
}

/** Container for the value of a non-blocking molecule.
  *
  * @tparam T The type of the value.
  */
private[jc] final case class MolValue[T](private[jc] val moleculeValue: T) extends AbsMolValue[T]

/** Container for the value of a blocking molecule.
  * The `hashCode` of a [[BlockingMolValue]] should depend only on the `hashCode` of the value `v`,
  * and not on the reply value (which is mutable). This is now implemented in the parent trait [[AbsMolValue]].
  *
  * @param replyValue The wrapper for the reply value, which will ultimately return a value of type R.
  * @tparam T The type of the value carried by the molecule.
  * @tparam R The type of the reply value.
  */
private[jc] final case class BlockingMolValue[T, R](
  private[jc] val moleculeValue: T,
  replyValue: AbsReplyEmitter[T, R]
) extends AbsMolValue[T] {
  override private[jc] def reactionSentNoReply: Boolean = replyValue.noReplyAttemptedYet // `true` if no value, no error, and no timeout
}

/** Abstract trait representing a molecule emitter.
  * This trait is not parameterized by type and is used in collections of molecules that do not require knowledge of molecule types.
  * Its only implementations are the classes [[B]] and [[M]].
  */
sealed trait Molecule extends PersistentHashCode {

  /** The name of the molecule. Used only for debugging.
    * This will be assigned automatically if using the [[b]] or [[m]] macros.
    */
  val name: String

  def isPipelined: Boolean = valIsPipelined

  def typeSymbol: Symbol = valTypeSymbol

  def siteIndex: Int = siteIndexValue

  def isSelfBlocking: Boolean = valSelfBlockingPool.exists {
    pool ⇒
      Thread.currentThread match {
        case t: SmartThread ⇒
          t.pool === pool
        case _ ⇒ false
      }
  }

  /** This is called by a [[ReactionSite]] when a molecule becomes bound to that reaction site.
    *
    * @param rs        Reaction site to which the molecule is now bound.
    * @param siteIndex Zero-based index of the input molecule at that reaction site.
    */
  private[jc] def setReactionSiteInfo(rs: ReactionSite, siteIndex: Int, typeSymbol: Symbol, pipelined: Boolean, selfBlocking: Option[Pool]): Unit = {
    reactionSiteString = rs.toString
    hasReactionSite = true
    siteIndexValue = siteIndex
    valTypeSymbol = typeSymbol
    valIsPipelined = pipelined
    valSelfBlockingPool = selfBlocking
  }

  /** Check whether the molecule is already bound to a reaction site.
    * Note that molecules can be emitted only if they are bound.
    *
    * @return True if already bound, false otherwise.
    */
  final def isBound: Boolean = hasReactionSite

  /** Check whether this molecule is already bound to a reaction site that's different from the given reaction site.
    *
    * @param rs A reaction site.
    * @return `None` if the molecule is not bound to any reaction site, or if it is bound to `rs`.
    *         Otherwise the molecule is already bound to a reaction site different from `rs`, so return
    *         the string representation of that reaction site as a non-empty option.
    */
  final private[jc] def isBoundToAnotherReactionSite(rs: ReactionSite): Option[String] =
    if (isBound && !reactionSiteWrapper.sameReactionSite(rs))
      Some(reactionSiteWrapper.toString)
    else
      None

  // All these variables will be assigned exactly once and will never change thereafter. It's not clear how best to enforce this in Scala.
  private var valIsPipelined: Boolean = false

  protected var reactionSiteWrapper: ReactionSiteWrapper[_, _] = ReactionSiteWrapper.noReactionSite(this)

  protected var valTypeSymbol: Symbol = _

  protected var valSelfBlockingPool: Option[Pool] = None

  protected var siteIndexValue: Int = -1

  protected var hasReactionSite: Boolean = false

  protected var reactionSiteString: String = _

  /** The list of reactions that can consume this molecule.
    *
    * Will be empty if the molecule emitter is not yet bound to any reaction site.
    * This value is used only for static analysis.
    */
  private[jc] lazy val consumingReactions: Array[Reaction] = reactionSiteWrapper.consumingReactions

  /** The set of all reactions that *potentially* emit this molecule as output.
    * Some of these reactions may evaluate a run-time condition to decide whether to emit the molecule; so emission is not guaranteed.
    *
    * Note that these reactions may be defined in any reaction sites, not necessarily at the site to which this molecule is bound.
    * The set of these reactions may change at run time if new reaction sites are written that output this molecule.
    *
    * This is used only during static analysis. This cannot be made a `lazy val` since static analysis can proceed before all emitting reactions are known.
    * Static analysis may be incomplete if that happens; but we can do little about this, since reaction sites are activated at run time.
    *
    * @return Empty set if the molecule is not yet bound to any reaction site.
    */
  final private[jc] def emittingReactions: Set[Reaction] = emittingReactionsSet.toSet

  private val emittingReactionsSet: mutable.Set[Reaction] = mutable.Set()

  // This is called by the reaction site only during the initial setup.
  // Each reaction site will add emitting reactions to all molecules it emits, including molecules bound to other reaction sites.
  // Once all reaction sites are activated, the set of emitting reactions for this molecule will never change.
  final private[jc] def addEmittingReaction(r: Reaction): Unit = {
    emittingReactionsSet += r
    ()
  }

  final def setLogLevel(logLevel: Int): Unit = reactionSiteWrapper.setLogLevel(logLevel)

  final def logSoup: String = reactionSiteWrapper.logSoup()

  val isBlocking: Boolean = false

  /** This is a `def` because we will only know whether this molecule is static after this molecule is bound to a reaction site, at run time.
    * This will be overridden by the [[M]] class (only non-blocking molecules can be static).
    */
  def isStatic: Boolean = false

  /** Prints a molecule's displayed name and a `/B` suffix for blocking molecules.
    *
    * @return A molecule's displayed name as string.
    */
  override def toString: String = (if (name.isEmpty) "<no name>" else name) + (if (isBlocking) "/B" else "") // This can't be a lazy val because `isBlocking` is overridden in derived classes.
}

/** Non-blocking molecule class. Instance is mutable until the molecule is bound to a reaction site and until all reactions involving this molecule are declared.
  *
  * @param name Name of the molecule, used for debugging only.
  * @tparam T Type of the value carried by the molecule.
  */
final class M[T](val name: String) extends (T => Unit) with Molecule {

  def unapply(arg: ReactionBodyInput): Option[T] = {
    val (index, inputMoleculeList) = arg
    inputMoleculeList.lift(index).map(_.asInstanceOf[MolValue[T]].moleculeValue)
  }

  /** Emit a non-blocking molecule.
    *
    * Note that static molecules can be emitted only by a reaction that consumed them, and not by other code.
    *
    * @param v Value to be put onto the emitted molecule.
    */
  def apply(v: T): Unit =
    if (isStatic)
      throw new ExceptionEmittingStaticMol(s"Error: static molecule $this cannot be emitted non-statically")
    else applyStatic(v)

  def apply()(implicit arg: TypeMustBeUnit[T]): Unit = apply(arg.getUnit)

  def applyStatic(v: T): Unit = reactionSiteWrapper.asInstanceOf[ReactionSiteWrapper[T, Unit]].emit(this, MolValue(v))

  def applyStatic()(implicit arg: TypeMustBeUnit[T]): Unit = applyStatic(arg.getUnit)

  /** Volatile reader for a molecule.
    * The molecule must be declared as static.
    *
    * @return The value carried by the static molecule when it was last emitted. Will throw exception if the static molecule has not yet been emitted.
    */
  def volatileValue: T = if (isBound) {
    if (isStatic)
      volatileValueRef.get
    else throw new Exception(s"In $reactionSiteWrapper: volatile reader requested for non-static molecule ($this)")
  }
  else throw new Exception(s"Molecule $name is not bound to any reaction site, cannot read volatile value")

  private[jc] def assignStaticMolVolatileValue(molValue: AbsMolValue[_]) =
    volatileValueRef.set(molValue.asInstanceOf[MolValue[T]].moleculeValue)

  private val volatileValueRef: AtomicReference[T] = new AtomicReference[T]()

  override def isStatic: Boolean = reactionSiteWrapper.isStatic

  override private[jc] def setReactionSiteInfo(rs: ReactionSite, index: Int, valType: Symbol, pipelined: Boolean, selfBlocking: Option[Pool]) = {
    super.setReactionSiteInfo(rs, index, valType, pipelined, selfBlocking)
    reactionSiteWrapper = rs.makeWrapper[T, Unit](this) // need to specify types for `makeWrapper`; set `Unit` instead of `R` for non-blocking molecules
  }
}

/** Represents the different states of the reply process.
  * Initially, the status is [[HaveReply]] with a `null` value.
  * Reply is successful if the emitting call does not time out. In this case, we have a reply value.
  * This is represented by [[HaveReply]] with a non-null value.
  * If the reply times out, there is still no reply value. This is represented by the AtomicBoolean flag [[AbsReplyEmitter.hasTimedOut]] set to `true`.
  * If the reaction finished but did not reply, it is an error condition. If the reaction finished and replied more than once, it is also an error condition.
  * After a reaction fails to reply, the emitting closure will put an error message into the status for that molecule. This is represented by [[ErrorNoReply]].
  * When a reaction replies more than once, it is too late to put an error message into the status for that molecule. So we do not have a status value for this situation.
  */
private[jc] sealed trait ReplyStatus

/** Indicates that the reaction body finished running but did not reply to the blocking molecule.
  *
  * @param message Error message (showing which other molecules did not receive replies, or received multiple replies).
  */
private[jc] final case class ErrorNoReply(message: String) extends ReplyStatus

/** If the value is not null, indicates that the reaction body correctly replied to the blocking molecule.
  * If the value is null, indicates that the reaction body has not yet replied.
  *
  * @param result The reply value.
  * @tparam R Type of the reply value.
  */
private[jc] final case class HaveReply[R](result: R) extends ReplyStatus

/** This trait contains the implementations of most methods for [[ReplyEmitter]] class.
  *
  * @tparam T Type of the value that the molecule carries.
  * @tparam R Type of the reply value.
  */
private[jc] sealed trait AbsReplyEmitter[T, R] {

  @volatile private var replyStatus: ReplyStatus = HaveReply[R](null.asInstanceOf[R]) // the `null` and the typecast will not be used because `replyStatus` will be either overwritten or ignored on timeout. This avoids a third case class in ReplyStatus, and the code can now be completely covered by tests.

  final private[jc] def getReplyStatus = replyStatus

  /** This is set by the reaction site in case there was a user error, such as not replying to a blocking molecule. */
  final private[jc] def setErrorStatus(message: String) = {
    replyStatus = ErrorNoReply(message)
  }

  /** This atomic mutable value is read and written only by reactions that perform reply actions.
    * Access to this variable must be guarded by [[semaphoreForReplyStatus]].
    */
  private val hasReply = new AtomicBoolean(false)

  /** This atomic mutable value is written only by the reaction that emitted the blocking molecule,
    * but read by reactions that perform the reply action with timeout checking.
    * Access to this variable must be guarded by [[semaphoreForReplyStatus]].
    */
  private val hasTimedOut = new AtomicBoolean(false)

  final private[jc] def setTimedOut() = hasTimedOut.set(true)

  final private[jc] def isTimedOut: Boolean = hasTimedOut.get

  final private[jc] def noReplyAttemptedYet: Boolean = !hasReply.get

  /** This semaphore blocks the emitter of a blocking molecule until a reply is received.
    * This semaphore is initialized only once when creating an instance of this
    * class. The semaphore will be acquired when emitting the molecule and released by the "reply"
    * action. The semaphore will never be used again once a reply is received.
    */
  private val semaphoreForEmitter = new Semaphore(0, false)

  /** This is used by the reaction that replies to the blocking molecule, in order to obtain
    * the reply status safely (without race conditions).
    * Initially, the semaphore has 1 permits.
    * The reaction that replies will use 2 permits. Therefore, one additional permit will be given by the emitter after the reply is received.
    */
  private val semaphoreForReplyStatus = new Semaphore(1, false)

  final private[jc] def acquireSemaphoreForEmitter(timeoutNanos: Option[Long]): Boolean =
    timeoutNanos match {
      case Some(nanos) => semaphoreForEmitter.tryAcquire(nanos, TimeUnit.NANOSECONDS)
      case None => semaphoreForEmitter.acquire(); true
    }

  final private[jc] def releaseSemaphoreForEmitter(): Unit = semaphoreForEmitter.release()

  final private[jc] def releaseSemaphoreForReply(): Unit = semaphoreForReplyStatus.release()

  final private[jc] def acquireSemaphoreForReply() = semaphoreForReplyStatus.acquire()

  /** Perform the reply action for a blocking molecule.
    * This is called by a reaction that consumed the blocking molecule.
    * The reply value will be received by the process that emitted the blocking molecule, and will unblock that process.
    * The reply value will not be received if the emitting process timed out on the blocking call, or if the reply was already made (then it is an error to reply again).
    *
    * @param r Value to reply with.
    * @return `true` if the reply was received normally, `false` if it was not received due to one of the above conditions.
    */
  final protected def performReplyAction(r: R): Boolean = {
    // TODO: simplify this code under the assumption that repeated replies are impossible
    val replyWasNotRepeated = hasReply.compareAndSet(false, true)

    // We return `true` only if this reply was not a repeated reply, and if we have no timeout.
    replyWasNotRepeated && {
      // We have not yet tried to reply.
      // This semaphore was released by the emitting reaction as it starts the blocking wait.
      acquireSemaphoreForReply()
      // We need to make sure the emitting reaction already started the blocking wait.
      // After acquiring this semaphore, it is safe to read and modify `replyStatus`.
      // The reply value will be assigned only if there was no timeout and no previous reply action.

      replyStatus = HaveReply(r)

      releaseSemaphoreForEmitter() // Unblock the reaction that emitted this blocking molecule.
      // That reaction will now set reply status and release semaphoreForReplyStatus again.

      acquireSemaphoreForReply() // Wait until the emitting reaction has set the timeout status.
      // After acquiring this semaphore, it is safe to read the reply status.
      !isTimedOut
    }
  }

  /** This is similar to [[performReplyAction]] except that user did not request the timeout checking, so we have fewer semaphores to deal with. */
  final protected def performReplyActionWithoutTimeoutCheck(r: R): Unit = {
    val replyWasNotRepeated = hasReply.compareAndSet(false, true)
    if (replyWasNotRepeated) {
      // We have not yet tried to reply.
      replyStatus = HaveReply(r)
      releaseSemaphoreForEmitter() // Unblock the reaction that emitted this blocking molecule.
    }
  }
}

/** Reply emitter for blocking molecules. This is a mutable class (the mutable parts are inherited from [[AbsReplyEmitter]]).
  *
  * @tparam T Type of the value carried by the molecule.
  * @tparam R Type of the value replied to the caller via the "reply" action.
  */
private[jc] final class ReplyEmitter[T, R] extends (R => Unit) with AbsReplyEmitter[T, R] {

  /** Perform a reply action for a blocking molecule without checking the timeout status (this is slightly faster).
    * For each blocking molecule consumed by a reaction, exactly one reply action should be performed within the reaction body.
    * If a timeout occurred after the reaction body started evaluating but before the reply action was performed, the reply value will not be actually sent anywhere.
    * This method will not fail in that case, but since it returns `Unit`, the user will not know whether the reply succeeded.
    *
    * @param r Value to reply with.
    * @return Unit value, regardless of whether the reply succeeded before timeout.
    */
  def apply(r: R): Unit = performReplyActionWithoutTimeoutCheck(r)

  /** Same but for molecules with type `R = Unit`. */
  def apply()(implicit arg: TypeMustBeUnit[R]): Unit = apply(arg.getUnit)

  /** Perform a reply action for a blocking molecule with a check of the timeout status.
    * For each blocking molecule consumed by a reaction, exactly one reply action should be performed within the reaction body.
    * If a timeout occurred after the reaction body started evaluating but before the reply action was performed, the reply value will not be actually sent anywhere.
    * This method will return `false` in that case.
    *
    * @param r Value to reply with.
    * @return `true` if the reply was successful, `false` if the blocking molecule timed out, or if a reply action was already performed.
    */
  def checkTimeout(r: R): Boolean = performReplyAction(r)

  /** Same as [[checkTimeout]] above but for molecules with type `R = Unit`, with shorter syntax. */
  def checkTimeout()(implicit arg: TypeMustBeUnit[R]): Boolean = checkTimeout(arg.getUnit)
}

/** Blocking molecule class. Instance is mutable until the molecule is bound to a reaction site and until all reactions involving this molecule are declared.
  *
  * @param name Name of the molecule, used for debugging only.
  * @tparam T Type of the value carried by the molecule.
  * @tparam R Type of the value replied to the caller via the "reply" action.
  */
final class B[T, R](val name: String) extends (T => R) with Molecule {
  override val isBlocking = true

  /** Emit a blocking molecule and receive a value when the reply action is performed, unless a timeout is reached.
    *
    * @param duration Timeout in any time interval.
    * @param v        Value to be put onto the emitted molecule.
    * @return Non-empty option if the reply was received; None on timeout.
    */
  def timeout(v: T)(duration: Duration): Option[R] = reactionSiteWrapper.asInstanceOf[ReactionSiteWrapper[T, R]]
    .emitAndAwaitReplyWithTimeout(duration.toNanos, this, v, new ReplyEmitter[T, R])

  /** Same but for molecules with type `T = Unit`; enables shorter syntax `b().timeout(1.second)`. */
  def timeout()(duration: Duration)(implicit arg: TypeMustBeUnit[T]): Option[R] = timeout(arg.getUnit)(duration)

  /** Perform the unapply matching and return a wrapped ReplyValue on success.
    *
    * @param arg The input molecule list, which should be a one-element list.
    * @return None if there was no match; Some(...) if the reaction inputs matched.
    */
  def unapply(arg: ReactionBodyInput): Option[(T, ReplyEmitter[T, R])] = {
    val (index, inputMoleculeList) = arg
    inputMoleculeList.lift(index)
      .map(_.asInstanceOf[BlockingMolValue[T, R]])
      .map { bmv => (bmv.moleculeValue, bmv.replyValue.asInstanceOf[ReplyEmitter[T, R]]) }
  }

  /** Emit a blocking molecule and receive a value when the reply action is performed.
    *
    * @param v Value to be put onto the emitted molecule.
    * @return The "reply" value.
    */
  def apply(v: T): R = reactionSiteWrapper.asInstanceOf[ReactionSiteWrapper[T, R]]
    .emitAndAwaitReply(this, v, new ReplyEmitter[T, R])

  /** This enables the short syntax `b()` instead of `b(())`, and will only work when `T == Unit`. */
  def apply()(implicit arg: TypeMustBeUnit[T]): R = apply(arg.getUnit)

  override private[jc] def setReactionSiteInfo(rs: ReactionSite, index: Int, valType: Symbol, pipelined: Boolean, selfBlocking: Option[Pool]) = {
    super.setReactionSiteInfo(rs, index, valType, pipelined, selfBlocking)
    reactionSiteWrapper = rs.makeWrapper[T, R](this) // need to specify types for `makeWrapper`
  }

}

/** Mix this trait into your class to make the has code persistent after the first time it's computed.
  *
  */
sealed trait PersistentHashCode {
  private lazy val hashCodeValue: Int = super.hashCode()

  override def hashCode(): Int = hashCodeValue
}
