package code.chymyst.jc

import Core._
import java.util.concurrent.{Semaphore, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import scala.collection.mutable
import scala.concurrent.duration.Duration

/** Convenience syntax: provides an `unapply` operation, so that users can write the chemical notation, such as
  * `a(x) + b(y) + ...`, in reaction input patterns.
  * Pattern-matching can be used on molecule values at will, for example:
  * {{{go { case a(MyCaseClass(x, y)) + b(Some(z)) if x > z => ... } }}}
  *
  * The chemical notation should be used only with the left-associative `+` operator grouped to the left.
  * Input patterns with a right-associative grouping of the `+` operator, for example `a(x) + ( b(y) + c(z) )`, are refused.
  */
object + {

  private def getRightMolecule(inputs: InputMoleculeList): Option[InputMoleculeList] = if (inputs.length < 2) None else Some(inputs.slice(inputs.length - 1, inputs.length))

  private def getLeftPortion(inputs: InputMoleculeList): Option[InputMoleculeList] = if (inputs.length < 2) None else Some(inputs.slice(0, inputs.length - 1))

  def unapply(inputs: InputMoleculeList): Option[(InputMoleculeList, InputMoleculeList)] =
    for {
      leftPortion <- getLeftPortion(inputs)
      rightMolecule <- getRightMolecule(inputs)
    } yield (leftPortion, rightMolecule)
}

/** Abstract container for molecule values. This is a common wrapper for values of blocking and non-blocking molecules.
  *
  * @tparam T Type of the molecule value.
  */
private[jc] sealed trait AbsMolValue[T] {
  private[jc] def getValue: T

  /** String representation of molecule values will omit printing the `Unit` values but print all other types normally.
    *
    * @return String representation of molecule value of type T. Unit values are printed as empty strings.
    */
  override final def toString: String = getValue match {
    case () => ""
    case v => v.toString
  }

  private[jc] def reactionSentNoReply: Boolean = false

  private[jc] def reactionSentRepeatedReply: Boolean = false
}

/** Container for the value of a non-blocking molecule.
  *
  * @param v The value of type T carried by the molecule.
  * @tparam T The type of the value.
  */
private[jc] final case class MolValue[T](v: T) extends AbsMolValue[T] {
  override private[jc] def getValue: T = v
}

/** Container for the value of a blocking molecule.
  *
  * @param v          The value of type T carried by the molecule.
  * @param replyValue The wrapper for the reply value, which will ultimately return a value of type R.
  * @tparam T The type of the value carried by the molecule.
  * @tparam R The type of the reply value.
  */
private[jc] final case class BlockingMolValue[T, R](v: T, replyValue: AbsReplyValue[T, R]) extends AbsMolValue[T] with PersistentHashCode {
  override private[jc] def getValue: T = v

  override private[jc] def reactionSentNoReply: Boolean = replyValue.noReplyAttemptedYet // no value, no error, and no timeout

  override private[jc] def reactionSentRepeatedReply: Boolean = replyValue.replyWasRepeated
}

/** Abstract molecule emitter trait.
  * This trait is not parameterized by type and is used in collections of molecules that do not require knowledge of molecule types.
  * Its only implementations are the classes [[B]] and [[M]].
  */
sealed trait Molecule extends PersistentHashCode {

  /** The name of the molecule. Used only for debugging.
    * This will be assigned automatically if using the [[b]] or [[m]] macros.
    */
  val name: String

  override def toString: String = (if (name.isEmpty) "<no name>" else name) + (if (isBlocking) "/B" else "")

  /** Check whether the molecule is already bound to a reaction site.
    * Note that molecules can be emitted only if they are bound.
    *
    * @return True if already bound, false otherwise.
    */
  final def isBound: Boolean = reactionSiteOpt.nonEmpty

  // This is @volatile because the reaction site will assign this variable possibly from another thread.
  @volatile private[jc] var reactionSiteOpt: Option[ReactionSite] = None

  /** A shorthand method to get the reaction site to which this molecule is bound.
    * This method should be used only when we are sure that the molecule is already bound.
    *
    * @return The reaction site to which the molecule is bound. If not yet bound, throws an exception.
    */
  final private[jc] def site: ReactionSite =
    reactionSiteOpt.getOrElse(throw new ExceptionNoReactionSite(s"Molecule $this is not bound to any reaction site"))

  /** The set of reactions that can consume this molecule.
    *
    * @return `None` if the molecule emitter is not yet bound to any reaction site.
    */
  final private[jc] def consumingReactions: Option[List[Reaction]] =
    reactionSiteOpt.map(_ => consumingReactionsSet)

  final private[jc] lazy val consumingReactionsSet: List[Reaction] =
    reactionSiteOpt.get.reactionInfos.keys.filter(_.inputMoleculesSet contains this).toList

  /** The set of all reactions that *potentially* emit this molecule as output.
    * Some of these reactions may evaluate a runtime condition to decide whether to emit the molecule; so emission is not guaranteed.
    *
    * Note that these reactions may be defined in any reaction sites, not necessarily at the site to which this molecule is bound.
    * The set of these reactions may change at run time if new reaction sites are written that output this molecule.
    *
    * @return Empty set if the molecule is not yet bound to any reaction site.
    */
  final private[jc] def emittingReactions: Set[Reaction] = emittingReactionsSet.toSet

  final private val emittingReactionsSet: mutable.Set[Reaction] = mutable.Set()

  // This is called by the reaction site only during the initial setup. Once the reaction site is activated, the set of emitting reactions will never change.
  final private[jc] def addEmittingReaction(r: Reaction): Unit = {
    emittingReactionsSet += r
    ()
  }

  final def setLogLevel(logLevel: Int): Unit =
    site.logLevel = logLevel

  final def logSoup: String = site.printBag

  val isBlocking: Boolean

  /** This is `lazy` because we will only know whether this molecule is a singleton after this molecule is bound to a reaction site, at run time. */
  lazy val isSingleton: Boolean = false
}

/** Non-blocking molecule class. Instance is mutable until the molecule is bound to a reaction site and until all reactions involving this molecule are declared.
  *
  * @param name Name of the molecule, used for debugging only.
  * @tparam T Type of the value carried by the molecule.
  */
final class M[T](val name: String) extends (T => Unit) with Molecule {

  val isBlocking = false

  def unapply(arg: InputMoleculeList): Option[T] =
    arg.headOption
      .map(_._2.asInstanceOf[MolValue[T]].v)

  /** Emit a non-blocking molecule.
    *
    * @param v Value to be put onto the emitted molecule.
    */
  def apply(v: T): Unit = site.emit[T](this, MolValue(v))

  def apply()(implicit ev: TypeIsUnit[T]): Unit = apply(ev.getUnit)

  /** Volatile reader for a molecule.
    * The molecule must be declared as a singleton.
    *
    * @return The value carried by the singleton when it was last emitted. Will throw exception if the singleton has not yet been emitted.
    */
  def volatileValue: T = reactionSiteOpt match {
    case Some(reactionSite) =>
      if (isSingleton)
        volatileValueContainer
      else throw new Exception(s"In $reactionSite: volatile reader requested for non-singleton ($this)")

    case None => throw new Exception("Molecule c is not bound to any reaction site")
  }

  private[jc] def assignSingletonVolatileValue(molValue: AbsMolValue[_]) =
    volatileValueContainer = molValue.asInstanceOf[MolValue[T]].getValue

  @volatile private var volatileValueContainer: T = _

  override lazy val isSingleton: Boolean =
    reactionSiteOpt.exists(_.singletonsDeclared.contains(this))

}

/** Represents the different states of the reply process.
  * Initially, the status is [[HaveReply]] with a `null` value.
  * Reply is successful if the emitting call does not time out. In this case, we have a reply value.
  * This is represented by [[HaveReply]] with a non-null value.
  * If the reply times out, there is still no reply value. This is represented by the AtomicBoolean flag [[AbsReplyValue.hasTimedOut]] set to `true`.
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

/** This trait contains the implementations of most methods for [[ReplyValue]] class.
  *
  * @tparam T Type of the value that the molecule carries.
  * @tparam R Type of the reply value.
  */
private[jc] sealed trait AbsReplyValue[T, R] {

  @volatile final private var replyStatus: ReplyStatus = HaveReply[R](null.asInstanceOf[R]) // the `null` and the typecast will not be used because `replyStatus` will be either overwritten or ignored on timeout. This avoids a third case class in ReplyStatus, and the code can now be completely covered by tests.

  final private[jc] def getReplyStatus = replyStatus

  /** This is set by the reaction site in case there was a user error, such as not replying to a blocking molecule. */
  final private[jc] def setErrorStatus(message: String) = {
    replyStatus = ErrorNoReply(message)
  }

  /** This atomic mutable value is read and written only by reactions that perform reply actions.
    * Access to this variable must be guarded by [[semaphoreForReplyStatus]].
    */
  final private val numberOfReplies = new AtomicInteger(0)

  /** This atomic mutable value is written only by the reaction that emitted the blocking molecule,
    * but read by reactions that perform the reply action with timeout checking.
    * Access to this variable must be guarded by [[semaphoreForReplyStatus]].
    */
  final private val hasTimedOut = new AtomicBoolean(false)

  final private[jc] def setTimedOut() = hasTimedOut.set(true)

  final private[jc] def isTimedOut: Boolean = hasTimedOut.get

  final private[jc] def noReplyAttemptedYet: Boolean = numberOfReplies.get === 0

  final private[jc] def replyWasRepeated: Boolean = numberOfReplies.get >= 2

  /** This semaphore blocks the emitter of a blocking molecule until a reply is received.
    * This semaphore is initialized only once when creating an instance of this
    * class. The semaphore will be acquired when emitting the molecule and released by the "reply"
    * action. The semaphore will never be used again once a reply is received.
    */
  final private val semaphoreForEmitter = new Semaphore(0, false)

  /** This is used by the reaction that replies to the blocking molecule, in order to obtain
    * the reply status safely (without race conditions).
    * Initially, the semaphore has 1 permits.
    * The reaction that replies will use 2 permits. Therefore, one additional permit will be given by the emitter after the reply is received.
    */
  final private val semaphoreForReplyStatus = new Semaphore(1, false)

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
    * @param x Value to reply with.
    * @return `true` if the reply was received normally, `false` if it was not received due to one of the above conditions.
    */
  final protected def performReplyAction(x: R): Boolean = {

    val replyWasNotRepeated = numberOfReplies.getAndIncrement() === 0

    val status = if (replyWasNotRepeated) {
      // We have not yet tried to reply.
      // This semaphore was released by the emitting reaction as it starts the blocking wait.
      acquireSemaphoreForReply()
      // We need to make sure the emitting reaction already started the blocking wait.
      // After acquiring this semaphore, it is safe to read and modify `replyStatus`.
      // The reply value will be assigned only if there was no timeout and no previous reply action.

      replyStatus = HaveReply(x)

      releaseSemaphoreForEmitter() // Unblock the reaction that emitted this blocking molecule.
      // That reaction will now set reply status and release semaphoreForReplyStatus again.

      acquireSemaphoreForReply() // Wait until the emitting reaction has set the timeout status.
      // After acquiring this semaphore, it is safe to read the reply status.
      !isTimedOut
    } else
      false // We already tried to reply, so nothing to be done now.

    status
  }

  /** This is similar to [[performReplyAction]] except that user did not request the timeout checking, so we have fewer semaphores to deal with. */
  final protected def performReplyActionWithoutTimeoutCheck(x: R): Unit = {
    val replyWasNotRepeated = numberOfReplies.getAndIncrement() === 0
    if (replyWasNotRepeated) {
      // We have not yet tried to reply.
      replyStatus = HaveReply(x)
      releaseSemaphoreForEmitter() // Unblock the reaction that emitted this blocking molecule.
    }
  }
}

/** Reply-value wrapper for blocking molecules. This is a mutable class.
  *
  * @tparam T Type of the value carried by the molecule.
  * @tparam R Type of the value replied to the caller via the "reply" action.
  */
private[jc] final class ReplyValue[T, R] extends (R => Unit) with AbsReplyValue[T, R] {

  /** Perform a reply action for a blocking molecule without checking the timeout status (this is slightly faster).
    * For each blocking molecule consumed by a reaction, exactly one reply action should be performed within the reaction body.
    *
    * @param x Value to reply with.
    * @return Unit value.
    */
  def apply(x: R): Unit = performReplyActionWithoutTimeoutCheck(x)

  /** Same but for molecules with type `R = Unit`. */
  def apply()(implicit ev: TypeIsUnit[R]): Unit = apply(ev.getUnit)

  /** Perform a reply action for a blocking molecule while checking the timeout status.
    * For each blocking molecule consumed by a reaction, exactly one reply action should be performed within the reaction body.
    *
    * @param x Value to reply with.
    * @return True if the reply was successful. False if the blocking molecule timed out, or if a reply action was already performed.
    */
  def checkTimeout(x: R): Boolean = performReplyAction(x)

  /** Same but for molecules with type `R = Unit`, with shorter syntax. */
  def checkTimeout()(implicit ev: TypeIsUnit[R]): Boolean = checkTimeout(ev.getUnit)
}

/** Blocking molecule class. Instance is mutable until the molecule is bound to a reaction site and until all reactions involving this molecule are declared.
  *
  * @param name Name of the molecule, used for debugging only.
  * @tparam T Type of the value carried by the molecule.
  * @tparam R Type of the value replied to the caller via the "reply" action.
  */
final class B[T, R](val name: String) extends (T => R) with Molecule {

  val isBlocking = true

  /** Emit a blocking molecule and receive a value when the reply action is performed, unless a timeout is reached.
    *
    * @param duration Timeout in any time interval.
    * @param v        Value to be put onto the emitted molecule.
    * @return Non-empty option if the reply was received; None on timeout.
    */
  def timeout(v: T)(duration: Duration): Option[R] =
    site.emitAndAwaitReplyWithTimeout[T, R](duration.toNanos, this, v, new ReplyValue[T, R])

  /** Same but for molecules with type `T = Unit`, with shorter syntax. */
  def timeout()(duration: Duration)(implicit ev: TypeIsUnit[T]): Option[R] = timeout(ev.getUnit)(duration)

  /** Perform the unapply matching and return a wrapped ReplyValue on success.
    *
    * @param arg The input molecule list, which should be a one-element list.
    * @return None if there was no match; Some(...) if the reaction inputs matched.
    */
  def unapply(arg: InputMoleculeList): Option[(T, ReplyValue[T, R])] =
    arg.headOption
      .map(_._2.asInstanceOf[BlockingMolValue[T, R]])
      .map { bmv => (bmv.v, bmv.replyValue.asInstanceOf[ReplyValue[T, R]]) }

  /** Emit a blocking molecule and receive a value when the reply action is performed.
    *
    * @param v Value to be put onto the emitted molecule.
    * @return The "reply" value.
    */
  def apply(v: T): R = site.emitAndAwaitReply[T, R](this, v, new ReplyValue[T, R])

  /** This enables the short syntax `b()` and will only work when `T == Unit`. */
  def apply()(implicit ev: TypeIsUnit[T]): R = apply(ev.getUnit)
}

/** Mix this trait into your class to make the has code persistent after the first time it's computed.
  *
  */
sealed trait PersistentHashCode {
  private lazy val hashCodeValue: Int = super.hashCode()

  override def hashCode(): Int = hashCodeValue
}
