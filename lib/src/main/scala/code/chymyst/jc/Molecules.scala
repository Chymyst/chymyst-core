package code.chymyst.jc

import Core._

import java.util.concurrent.{Semaphore, TimeUnit}

import scala.collection.mutable
import scala.concurrent.duration.Duration

/**
  * Convenience syntax: users can write a(x)+b(y) in reaction patterns.
  * Pattern-matching can be extended to molecule values as well, for example
  * {{{ { case a(MyCaseClass(x,y)) + b(Some(z)) => ... } }}}
  *
  * @return an unapply operation
  */
object + {
  def unapply(attr:Any): Option[(Any, Any)] = Some((attr,attr))
}

// Abstract container for molecule values.
private[jc] sealed trait AbsMolValue[T] {
  def getValue: T

  override def toString: String = getValue match { case () => ""; case v@_ => v.asInstanceOf[T].toString }

  def replyWasNotSentDueToError: Boolean = false
  def replyWasSentRepeatedly: Boolean = false
}

/** Container for the value of a non-blocking molecule.
  *
  * @param v The value of type T carried by the molecule.
  * @tparam T The type of the value.
  */
private[jc] final case class MolValue[T](v: T) extends AbsMolValue[T] {
  override def getValue: T = v
}

/** Container for the value of a blocking molecule.
  *
  * @param v The value of type T carried by the molecule.
  * @param replyValue The wrapper for the reply value, which will ultimately return a value of type R.
  * @tparam T The type of the value carried by the molecule.
  * @tparam R The type of the reply value.
  */
private[jc] final case class BlockingMolValue[T,R](v: T, replyValue: AbsReplyValue[T,R]) extends AbsMolValue[T] with PersistentHashCode {
  override def getValue: T = v

  override def replyWasNotSentDueToError: Boolean = replyValue.result.isEmpty && !replyValue.replyTimedOut

  override def replyWasSentRepeatedly: Boolean = replyValue.replyWasRepeated
}

/** Abstract molecule emitter class.
  * This class is not parameterized by type and is used in collections of molecules that do not require knowledge of molecule types.
  *
  */
trait Molecule extends PersistentHashCode {

  val name: String

  override def toString: String = (if (name.isEmpty) "<no name>" else name) + (if (isBlocking) "/B" else "")

  /** Check whether the molecule is already bound to a reaction site.
    * Note that molecules can be emitted only if they are bound.
    *
    * @return True if already bound, false otherwise.
    */
  def isBound: Boolean = reactionSiteOpt.nonEmpty

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
  private[jc] def consumingReactions: Option[Set[Reaction]] = reactionSiteOpt.map(_ => consumingReactionsSet)

  private lazy val consumingReactionsSet: Set[Reaction] = reactionSiteOpt.get.reactionInfos.keys.filter(_.inputMolecules contains this).toSet

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

  private[jc] def addEmittingReaction(r: Reaction): Unit = {
    emittingReactionsSet += r
    ()
  }

  def setLogLevel(logLevel: Int): Unit =
    site.logLevel = logLevel

  def logSoup: String = site.printBag

  val isBlocking: Boolean

  lazy val isSingleton: Boolean = reactionSiteOpt.exists(_.singletonsDeclared.contains(this))
}

private[jc] trait NonblockingMolecule[T] extends Molecule {

  val isBlocking = false

  def unapplyInternal(arg: UnapplyArg): Option[T] = arg match {

    case UnapplyCheckSimple(inputMoleculesProbe) =>   // This is used only by `_go`.
      inputMoleculesProbe += this
      Some(null.asInstanceOf[T]) // hack for testing only. This value will not be used.

    // When we are gathering information about the input molecules, `unapply` will always return Some(...),
    // so that any pattern-matching on arguments will continue with null (since, at this point, we have no values).
    // Any pattern-matching will work unless null fails.
    // This is used just before running the actual reactions, to determine which ones pass all the pattern-matching tests.
    // We also gather the information about the molecule values actually used by the reaction, in case the reaction can start.
    case UnapplyRunCheck(moleculeValues, usedInputs) =>
      for {
        v <- moleculeValues.getOne(this)
      } yield {
        usedInputs += (this -> v)
        v.asInstanceOf[AbsMolValue[T]].getValue
      }

    // This is used when running the chosen reaction.
    case UnapplyRun(moleculeValues) => moleculeValues.get(this)
      .map(_.asInstanceOf[MolValue[T]].getValue)
  }

}
private[jc] trait BlockingMolecule[T, R] extends Molecule {

  /** This type will be ReplyValue[T,R] or EmptyReplyValue[R] depending on the class that inherits from BlockingMolecule.
    */
  type Reply

  val isBlocking = true

  override lazy val isSingleton = false

  /** Emit a blocking molecule and receive a value when the reply action is performed, unless a timeout is reached.
    *
    * @param duration Timeout in any time interval.
    * @param v Value to be put onto the emitted molecule.
    * @return Non-empty option if the reply was received; None on timeout.
    */
  def timeout(duration: Duration)(v: T): Option[R] =
    site.emitAndAwaitReplyWithTimeout[T,R](duration.toNanos, this, v, new ReplyValue[T,R])

  /** Perform the unapply matching and return a generic ReplyValue.
    * The specific implementation of unapply will possibly downcast this to EmptyReplyValue.
    *
    * @param arg One of the UnapplyArg case classes supplied by the reaction site at different phases of making decisions about what reactions to run.
    * @return None if there was no match; Some(...) if the reaction inputs matched.
    *         Also note that a side effect is performed on the mutable data inside UnapplyArg.
    *         In this way, {{{isDefinedAt}}} is used to gather data about the input molecule values.
    */
  def unapplyInternal(arg: UnapplyArg): Option[(T, Reply)] = arg match {

    case UnapplyCheckSimple(inputMoleculesProbe) =>   // used only by _go
      inputMoleculesProbe += this
      Some((null, null).asInstanceOf[(T, Reply)]) // hack for testing purposes only:
    // The null value will not be used in any production code since _go is private.

    // This is used just before running the actual reactions, to determine which ones pass all the pattern-matching tests.
    // We also gather the information about the molecule values actually used by the reaction, in case the reaction can start.
    case UnapplyRunCheck(moleculeValues, usedInputs) =>
      for {
        v <- moleculeValues.getOne(this)
      } yield {
        usedInputs += (this -> v)
        (v.getValue, null).asInstanceOf[(T, Reply)] // hack for verifying isDefinedAt:
        // The null value will not be used, since the reply value is always matched unconditionally.
      }

    // This is used when running the chosen reaction.
    case UnapplyRun(moleculeValues) => moleculeValues.get(this).map {
      case BlockingMolValue(v, srv) => (v, srv).asInstanceOf[(T, Reply)]
      case m@_ =>
        throw new ExceptionNoWrapper(s"Internal error: molecule $this with no value wrapper around value $m")
    }
  }

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

  override def unapply(arg: UnapplyArg): Option[(T, EmptyReplyValue[T])] = unapplyInternal(arg)
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

  override def unapply(arg: UnapplyArg): Option[(Unit, EmptyReplyValue[Unit])] = unapplyInternal(arg)
}

/** Non-blocking molecule class. Instance is mutable until the molecule is bound to a reaction site and until all reactions involving this molecule are declared.
  *
  * @param name Name of the molecule, used for debugging only.
  * @tparam T Type of the value carried by the molecule.
  */
class M[T](val name: String) extends (T => Unit) with NonblockingMolecule[T] {
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
  def volatileValue: T = site.getVolatileValue(this)

  def hasVolatileValue: Boolean = site.hasVolatileValue(this)

  def unapply(arg: UnapplyArg): Option[T] = unapplyInternal(arg)
}

/** This trait contains implementations of most methods for the [[ReplyValue]] and [[EmptyReplyValue]] classes.
  *
  * result Reply value as {{{Option[R]}}}. Initially this is None, and it may be assigned at most once by the
  *               "reply" action if the reply is "valid" (i.e. not timed out).
  * semaphore Mutable semaphore reference. This is initialized only once when creating an instance of this
  *                  class. The semaphore will be acquired when emitting the molecule and released by the "reply"
  *                  action. The semaphore will be destroyed and never initialized again once a reply is received.
  * errorMessage Optional error message, to notify the caller or to raise an exception when the user made a
  *                     mistake in chemistry.
  * replyTimeout Will be set to "true" if the molecule was emitted with a timeout and the timeout was reached.
  * replyRepeated Will be set to "true" if the molecule received a reply more than once.

  *
  * @tparam T Type of the value that the molecule carries.
  * @tparam R Type of the reply value.
  */
private[jc] trait AbsReplyValue[T, R] {

  @volatile var result: Option[R] = None

  @volatile private var semaphore: Semaphore = {
    val s = new Semaphore(0, false); s.drainPermits(); s
  }

  @volatile var errorMessage: Option[String] = None

  @volatile var replyTimedOut: Boolean = false

  @volatile var replyWasRepeated: Boolean = false

  private[jc] def releaseSemaphore(): Unit = synchronized {
    if (Option(semaphore).isDefined) semaphore.release()
  }

  private[jc] def deleteSemaphore(): Unit = synchronized {
    semaphore = null
  }

  private[jc] def acquireSemaphore(timeoutNanos: Option[Long]): Boolean =
    if (Option(semaphore).isDefined)
      timeoutNanos match {
        case Some(nanos) => semaphore.tryAcquire(nanos, TimeUnit.NANOSECONDS)
        case None => semaphore.acquire(); true
      }
    else false

  /** Perform the reply action for a blocking molecule.
    * This is called by a reaction that consumed the blocking molecule.
    * The reply value will be received by the process that emitted the blocking molecule, and will unblock that process.
    * The reply value will not be received if the emitting process timed out on the blocking call, or if the reply was already made (then it is an error to reply again).
    *
    * @param x Value to reply with.
    * @return {{{true}}} if the reply was received normally, {{{false}}} if it was not received due to one of the above conditions.
    */
  protected def performReplyAction(x: R): Boolean = synchronized {
    // The reply value will be assigned only if there was no timeout and no previous reply action.
    if (!replyTimedOut && !replyWasRepeated && result.isEmpty) {
      result = Some(x)
    } else if (!replyTimedOut && result.nonEmpty) {
      replyWasRepeated = true
    }

    releaseSemaphore()

    !replyTimedOut && !replyWasRepeated
  }
}

/** Specialized reply-value wrapper for blocking molecules with Unit reply values. This is a mutable class.
  *
  * @tparam T Type of the value carried by the molecule.
  */
private[jc] class EmptyReplyValue[T] extends ReplyValue[T, Unit] with (()=>Boolean) {
  /** Perform a reply action for blocking molecules with Unit reply values. The reply action can use the syntax `r()` without deprecation warnings.
    * For each blocking molecule consumed by a reaction, exactly one reply action should be performed within the reaction body.
    *
    * @return True if the reply was successful. False if the blocking molecule timed out, or if a reply action was already performed.
    */
  override def apply(): Boolean = performReplyAction(())
}

/** Reply-value wrapper for blocking molecules. This is a mutable class.
  *
  * @tparam T Type of the value carried by the molecule.
  * @tparam R Type of the value replied to the caller via the "reply" action.
  */
private[jc] class ReplyValue[T, R] extends (R => Boolean) with AbsReplyValue[T, R] {

  /** Perform a reply action for a blocking molecule.
    * For each blocking molecule consumed by a reaction, exactly one reply action should be performed within the reaction body.
    *
    * @param x Value to reply with.
    * @return True if the reply was successful. False if the blocking molecule timed out, or if a reply action was already performed.
    */
  def apply(x: R): Boolean = performReplyAction(x)
}

/** Blocking molecule class. Instance is mutable until the molecule is bound to a reaction site and until all reactions involving this molecule are declared.
  *
  * @param name Name of the molecule, used for debugging only.
  * @tparam T Type of the value carried by the molecule.
  * @tparam R Type of the value replied to the caller via the "reply" action.
  */
class B[T, R](val name: String) extends (T => R) with BlockingMolecule[T, R] {

  type Reply <: ReplyValue[T, R]

  /** Emit a blocking molecule and receive a value when the reply action is performed.
    *
    * @param v Value to be put onto the emitted molecule.
    * @return The "reply" value.
    */
  def apply(v: T): R =
    site.emitAndAwaitReply[T,R](this, v, new ReplyValue[T,R])

  def unapply(arg: UnapplyArg): Option[(T, Reply)] = unapplyInternal(arg)
}

sealed trait UnapplyArg // The disjoint union type for arguments passed to the unapply methods.
private[jc] final case class UnapplyCheckSimple(inputMolecules: mutable.MutableList[Molecule]) extends UnapplyArg // used only for `_go` and in tests
private[jc] final case class UnapplyRunCheck(moleculeValues: MoleculeBag, usedInputs: MutableLinearMoleculeBag) extends UnapplyArg // used for checking that reaction values pass the pattern-matching, before running the reaction
private[jc] final case class UnapplyRun(moleculeValues: LinearMoleculeBag) extends UnapplyArg // used for running the reaction

/** Mix this trait into your class to make the has code persistent after the first time it's computed.
  *
  */
trait PersistentHashCode {
  private lazy val hashCodeValue: Int = super.hashCode()
  override def hashCode(): Int = hashCodeValue
}
