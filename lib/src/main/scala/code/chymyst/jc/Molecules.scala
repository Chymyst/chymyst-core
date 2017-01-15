package code.chymyst.jc

import Core._
import java.util.concurrent.{Semaphore, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import scala.collection.mutable
import scala.concurrent.duration.Duration

/** Convenience syntax: users can write a(x) + b(y) in reaction patterns.
  * Pattern-matching can be extended to molecule values as well, for example
  * {{{ { case a(MyCaseClass(x,y)) + b(Some(z)) => ... } }}}
  *
  * @return an unapply operation
  */
object + {
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

  /** String representation of molecule values will omit printing the {{{Unit}}} values but print all other types normally.
    *
    * @return String representation of molecule value of type T.
    */
  override def toString: String = getValue match {
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
  * @param v The value of type T carried by the molecule.
  * @param replyValue The wrapper for the reply value, which will ultimately return a value of type R.
  * @tparam T The type of the value carried by the molecule.
  * @tparam R The type of the reply value.
  */
private[jc] final case class BlockingMolValue[T,R](v: T, replyValue: AbsReplyValue[T,R]) extends AbsMolValue[T] with PersistentHashCode {
  override private[jc] def getValue: T = v

  override private[jc] def reactionSentNoReply: Boolean = replyValue.noReplyAttemptedYet // no value, no error, and no timeout

  override private[jc] def reactionSentRepeatedReply: Boolean = replyValue.replyWasRepeated
}

/** Abstract molecule emitter class.
  * This class is not parameterized by type and is used in collections of molecules that do not require knowledge of molecule types.
  *
  */
sealed trait Molecule extends PersistentHashCode {

  val name: String

  override def toString: String = (if (name.isEmpty) "<no name>" else name) + (if (isBlocking) "/B" else "")

  /** Check whether the molecule is already bound to a reaction site.
    * Note that molecules can be emitted only if they are bound.
    *
    * @return True if already bound, false otherwise.
    */
  def isBound: Boolean = reactionSiteOpt.nonEmpty

  // This is @volatile because the reaction site will assign this variable possibly from another thread.
  @volatile private[jc] var reactionSiteOpt: Option[ReactionSite] = None

  /** A shorthand method to get the reaction site to which this molecule is bound.
    * This method should be used only when we are sure that the molecule is already bound.
    *
    * @return The reaction site to which the molecule is bound. If not yet bound, throws an exception.
    */
  private[jc] def site: ReactionSite =
    reactionSiteOpt.getOrElse(throw new ExceptionNoReactionSite(s"Molecule $this is not bound to any reaction site"))

  /** The set of reactions that can consume this molecule.
    *
    * @return {{{None}}} if the molecule emitter is not yet bound to any reaction site.
    */
  private[jc] def consumingReactions: Option[List[Reaction]] =
    reactionSiteOpt.map(_ => consumingReactionsSet)

  private lazy val consumingReactionsSet: List[Reaction] =
    reactionSiteOpt.get.reactionInfos.keys.filter(_.inputMolecules contains this).toList

  /** The set of all reactions that *potentially* emit this molecule as output.
    * Some of these reactions may evaluate a runtime condition to decide whether to emit the molecule; so emission is not guaranteed.
    *
    * Note that these reactions may be defined in any reaction sites, not necessarily at the site to which this molecule is bound.
    * The set of these reactions may change at run time if new reaction sites are written that output this molecule.
    *
    * @return Empty set if the molecule is not yet bound to any reaction site.
    */
  private[jc] def emittingReactions: Set[Reaction] = emittingReactionsSet.toSet

  private val emittingReactionsSet: mutable.Set[Reaction] = mutable.Set()

  // This is called by the reaction site only during the initial setup. Once the reaction site is activated, the set of emitting reactions will never change.
  private[jc] def addEmittingReaction(r: Reaction): Unit = {
    emittingReactionsSet += r
    ()
  }

  def setLogLevel(logLevel: Int): Unit =
    site.logLevel = logLevel

  def logSoup: String = site.printBag

  val isBlocking: Boolean

  lazy val isSingleton: Boolean =
    reactionSiteOpt.exists(_.singletonsDeclared.contains(this))
}

private[jc] sealed trait NonblockingMolecule[T] extends Molecule {

  val isBlocking = false

  def unapply(arg: InputMoleculeList): Option[T] =
    arg.headOption
      .map(_._2.asInstanceOf[MolValue[T]].v)
}

private[jc] sealed trait BlockingMolecule[T, R] extends Molecule {

  /** This type will be ReplyValue[T,R] or EmptyReplyValue[R] depending on the class that inherits from BlockingMolecule.
    */
  type Reply <: AbsReplyValue[T, R]

  val isBlocking = true

  override lazy val isSingleton = false

  /** Emit a blocking molecule and receive a value when the reply action is performed, unless a timeout is reached.
    *
    * @param duration Timeout in any time interval.
    * @param v        Value to be put onto the emitted molecule.
    * @return Non-empty option if the reply was received; None on timeout.
    */
  def timeout(duration: Duration)(v: T): Option[R] =
    site.emitAndAwaitReplyWithTimeout[T, R](duration.toNanos, this, v, new ReplyValue[T, R])

  /** Perform the unapply matching and return a generic ReplyValue on success.
    * The specific implementation of unapply will possibly downcast this to EmptyReplyValue.
    *
    * @param arg The input molecule list, which should be a one-element list.
    * @return None if there was no match; Some(...) if the reaction inputs matched.
    */
  def unapplyInternal(arg: InputMoleculeList): Option[(T, Reply)] =
    arg.headOption
      .map(_._2.asInstanceOf[BlockingMolValue[T, R]])
      .map { bmv => (bmv.v, bmv.replyValue.asInstanceOf[Reply]) }
}

/** Specialized class for non-blocking molecule emitters with empty value.
  * These molecules can be emitted with syntax a() without a deprecation warning.
  * Macro call m[Unit] returns this type.
  *
  * @param name Name of the molecule, used for debugging only.
  */
final class E(name: String) extends M[Unit](name) {
  def apply(): Unit = site.emit[Unit](this, MolValue(()))
}

/** Specialized class for blocking molecule emitters with empty value (but non-empty reply).
  * These molecules can be emitted with syntax f() without a deprecation warning.
  * Macro call b[Unit, T] returns this type when T is not Unit.
  *
  * @param name Name of the molecule, used for debugging only.
  * @tparam R Type of the value replied to the caller via the "reply" action.
  */
final class EB[R](name: String) extends B[Unit, R](name) {
  def apply(): R = site.emitAndAwaitReply[Unit, R](this, (), new ReplyValue[Unit, R])

  def timeout(duration: Duration)(): Option[R] =
    site.emitAndAwaitReplyWithTimeout[Unit, R](duration.toNanos, this, (), new ReplyValue[Unit, R])
}

/** Specialized class for blocking molecule emitters with non-empty value and empty reply.
  * The reply action for these molecules can be performed with syntax r() without a deprecation warning.
  * Example: go { case ef(x, r) => r() }
  *
  * Macro call b[T, Unit] returns this type when T is not Unit.
  *
  * @param name Name of the molecule, used for debugging only.
  * @tparam T Type of the value carried by the molecule.
  */
final class BE[T](name: String) extends B[T, Unit](name) {

  override type Reply = EmptyReplyValue[T]

  override def apply(v: T): Unit = site.emitAndAwaitReply[T, Unit](this, v, new EmptyReplyValue[T])

  override def timeout(duration: Duration)(v: T): Option[Unit] =
    site.emitAndAwaitReplyWithTimeout[T, Unit](duration.toNanos, this, v, new EmptyReplyValue[T])

  override def unapply(arg: InputMoleculeList): Option[(T, EmptyReplyValue[T])] = unapplyInternal(arg)
}

/**Specialized class for blocking molecule emitters with empty value and empty reply.
  * These molecules can be emitted with syntax fe() without a deprecation warning.
  * The reply action for these molecules can be performed with syntax r() without a deprecation warning.
  * Example: go { case ef(x, r) => r() }
  *
  * Macro call b[Unit, Unit] returns this type.
  *
  * @param name Name of the molecule, used for debugging only.
  */
final class EE(name: String) extends B[Unit, Unit](name) {

  type Reply = EmptyReplyValue[Unit]

  def apply(): Unit = site.emitAndAwaitReply[Unit, Unit](this, (), new EmptyReplyValue[Unit])

  def timeout(duration: Duration)(): Option[Unit] =
    site.emitAndAwaitReplyWithTimeout[Unit, Unit](duration.toNanos, this, (), new EmptyReplyValue[Unit])

  override def unapply(arg: InputMoleculeList): Option[(Unit, EmptyReplyValue[Unit])] = unapplyInternal(arg)
}

/** Non-blocking molecule class. Instance is mutable until the molecule is bound to a reaction site and until all reactions involving this molecule are declared.
  *
  * @param name Name of the molecule, used for debugging only.
  * @tparam T Type of the value carried by the molecule.
  */
sealed class M[T](val name: String) extends (T => Unit) with NonblockingMolecule[T] {
  /** Emit a non-blocking molecule.
    *
    * @param v Value to be put onto the emitted molecule.
    */
  def apply(v: T): Unit = site.emit[T](this, MolValue(v))

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

  @volatile private[jc] var volatileValueContainer: T = _
}

/** Represent the different states of the reply process.
  * Initially, the status is [[HaveReply]] with a {{{null}}} value.
  * Reply is successful if the emitting call does not time out. In this case, we have a reply value.
  * This is represented by [[HaveReply]] with a non-null value.
  * If the reply times out, there is still no reply value. This is represented by the AtomicBoolean flag hasTimedOut set to {{{true}}}.
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

/** This trait contains the implementations of most methods for the [[ReplyValue]] and [[EmptyReplyValue]] classes.
  *
  * @tparam T Type of the value that the molecule carries.
  * @tparam R Type of the reply value.
  */
private[jc] trait AbsReplyValue[T, R] {

  @volatile private var replyStatus: ReplyStatus = HaveReply[R](null.asInstanceOf[R]) // the `null` and the typecast will not be used because `replyStatus` will be either overwritten or ignored on timeout. This avoids a third case class in ReplyStatus, and the code can now be completely covered by tests.

  private[jc] def getReplyStatus = replyStatus

  private[jc] def setErrorStatus(message: String) = {
    replyStatus = ErrorNoReply(message)
  }

  /** This atomic mutable value is read and written only by reactions that perform reply actions.
    * Access to this variable must be guarded by [[semaphoreForReplyStatus]].
    */
  private val numberOfReplies = new AtomicInteger(0)

  /** This atomic mutable value is written only by the reaction that emitted the blocking molecule,
    * but read by reactions that perform the reply action with timeout checking.
    * Access to this variable must be guarded by [[semaphoreForReplyStatus]].
    */
  private val hasTimedOut = new AtomicBoolean(false)

  private[jc] def setTimedOut() = hasTimedOut.set(true)

  private[jc] def isTimedOut: Boolean = hasTimedOut.get

  private[jc] def noReplyAttemptedYet: Boolean = numberOfReplies.get === 0

  private[jc] def replyWasRepeated: Boolean = numberOfReplies.get >= 2

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

  private[jc] def acquireSemaphoreForEmitter(timeoutNanos: Option[Long]): Boolean =
    timeoutNanos match {
      case Some(nanos) => semaphoreForEmitter.tryAcquire(nanos, TimeUnit.NANOSECONDS)
      case None => semaphoreForEmitter.acquire(); true
    }

  private[jc] def releaseSemaphoreForEmitter(): Unit = semaphoreForEmitter.release()

  private[jc] def releaseSemaphoreForReply(): Unit = semaphoreForReplyStatus.release()

  private[jc] def acquireSemaphoreForReply() = semaphoreForReplyStatus.acquire()

  /** Perform the reply action for a blocking molecule.
    * This is called by a reaction that consumed the blocking molecule.
    * The reply value will be received by the process that emitted the blocking molecule, and will unblock that process.
    * The reply value will not be received if the emitting process timed out on the blocking call, or if the reply was already made (then it is an error to reply again).
    *
    * @param x Value to reply with.
    * @return {{{true}}} if the reply was received normally, {{{false}}} if it was not received due to one of the above conditions.
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
      false  // We already tried to reply, so nothing to be done now.

    status
  }

  final protected def performReplyActionWithoutTimeoutCheck(x: R): Unit = {
    val replyWasNotRepeated = numberOfReplies.getAndIncrement() === 0
    if (replyWasNotRepeated) {
      // We have not yet tried to reply.
      replyStatus = HaveReply(x)
      releaseSemaphoreForEmitter() // Unblock the reaction that emitted this blocking molecule.
    }
  }
}

/** Specialized reply-value wrapper for blocking molecules with Unit reply values. This is a mutable class.
  *
  * @tparam T Type of the value carried by the molecule.
  */
private[jc] final class EmptyReplyValue[T] extends ReplyValue[T, Unit] with (() => Unit) {
  /** Perform a reply action for blocking molecules with Unit reply values. The reply action can use the syntax `r()` without deprecation warnings.
    * For each blocking molecule consumed by a reaction, exactly one reply action should be performed within the reaction body.
    *
    * @return True if the reply was successful. False if the blocking molecule timed out, or if a reply action was already performed.
    */
  override def apply(): Unit = performReplyActionWithoutTimeoutCheck(())

  def checkTimeout(): Boolean = performReplyAction(())
}

/** Reply-value wrapper for blocking molecules. This is a mutable class.
  *
  * @tparam T Type of the value carried by the molecule.
  * @tparam R Type of the value replied to the caller via the "reply" action.
  */
private[jc] class ReplyValue[T, R] extends (R => Unit) with AbsReplyValue[T, R] {

  /** Perform a reply action for a blocking molecule without checking the timeout status (this is slightly faster).
    * For each blocking molecule consumed by a reaction, exactly one reply action should be performed within the reaction body.
    *
    * @param x Value to reply with.
    * @return Unit value.
    */
  def apply(x: R): Unit = performReplyActionWithoutTimeoutCheck(x)

  /** Perform a reply action for a blocking molecule while checking the timeout status.
    * For each blocking molecule consumed by a reaction, exactly one reply action should be performed within the reaction body.
    *
    * @param x Value to reply with.
    * @return True if the reply was successful. False if the blocking molecule timed out, or if a reply action was already performed.
    */
  def checkTimeout(x: R): Boolean = performReplyAction(x)
}

/** Blocking molecule class. Instance is mutable until the molecule is bound to a reaction site and until all reactions involving this molecule are declared.
  *
  * @param name Name of the molecule, used for debugging only.
  * @tparam T Type of the value carried by the molecule.
  * @tparam R Type of the value replied to the caller via the "reply" action.
  */
sealed class B[T, R](val name: String) extends (T => R) with BlockingMolecule[T, R] {

  type Reply <: ReplyValue[T, R]

  /** Emit a blocking molecule and receive a value when the reply action is performed.
    *
    * @param v Value to be put onto the emitted molecule.
    * @return The "reply" value.
    */
  def apply(v: T): R =
    site.emitAndAwaitReply[T,R](this, v, new ReplyValue[T,R])

  def unapply(arg: InputMoleculeList): Option[(T, Reply)] = unapplyInternal(arg)
}

/** Mix this trait into your class to make the has code persistent after the first time it's computed.
  *
  */
trait PersistentHashCode {
  private lazy val hashCodeValue: Int = super.hashCode()
  override def hashCode(): Int = hashCodeValue
}
