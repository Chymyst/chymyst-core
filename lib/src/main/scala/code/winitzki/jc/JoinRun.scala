package code.winitzki.jc

/*
Join Calculus (JC) is a micro-framework for declarative concurrency.

JC is basically “Actors” made type-safe, stateless, and more high-level.

The code is inspired by previous implementations by He Jiansen (https://github.com/Jiansen/ScalaJoin, 2011)
and Philipp Haller (http://lampwww.epfl.ch/~phaller/joins/index.html, 2008).
  * */

import java.util.UUID
import java.util.concurrent.{Semaphore, TimeUnit}

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.reflect.ClassTag

object JoinRun {

  sealed trait InputPatternType {
    def isUnconditional: Boolean = this match {
      case Wildcard | SimpleVar => true
      case _ => false
    }
  }

  case object Wildcard extends InputPatternType

  case object SimpleVar extends InputPatternType

  final case class SimpleConst(v: Any) extends InputPatternType

  final case class OtherInputPattern(matcher: PartialFunction[Any, Unit]) extends InputPatternType

  case object UnknownInputPattern extends InputPatternType

  sealed trait OutputPatternType

  final case class ConstOutputValue(v: Any) extends OutputPatternType

  case object OtherOutputPattern extends OutputPatternType

  sealed trait GuardPresenceType {
    def knownFalse: Boolean = this match {
      case GuardAbsent => true
      case _ => false
    }
  }

  case object GuardPresent extends GuardPresenceType

  case object GuardAbsent extends GuardPresenceType

  case object GuardPresenceUnknown extends GuardPresenceType

  /** Compile-time information about an input molecule pattern in a reaction.
    *
    * @param molecule The molecule injector value that represents the input molecule.
    * @param flag     Type of the input pattern: wildcard, constant match, etc.
    * @param sha1     Hash sum of the source code (AST tree) of the input pattern.
    */
  final case class InputMoleculeInfo(molecule: Molecule, flag: InputPatternType, sha1: String) {
    /** Determine whether this input molecule pattern is weaker than another pattern.
      * Pattern a(xxx) is weaker than b(yyy) if a==b and if anything matched by yyy will also be matched by xxx.
      *
      * @param info The input molecule info for another input molecule.
      * @return Some(true) if we can surely determine that this matcher is weaker than another;
      *         Some(false) if we can surely determine that this matcher is not weaker than another;
      *         None if we cannot determine anything because information is insufficient.
      */
    private[jc] def matcherIsWeakerThan(info: InputMoleculeInfo): Option[Boolean] = {
      if (molecule != info.molecule) Some(false)
      else flag match {
        case Wildcard | SimpleVar => Some(true)
        case OtherInputPattern(matcher1) => info.flag match {
          case SimpleConst(c) => Some(matcher1.isDefinedAt(c))
          case OtherInputPattern(_) => if (sha1 == info.sha1) Some(true) else None // We can reliably determine identical matchers.
          case _ => Some(false) // Here we can reliably determine that this matcher is not weaker.
        }
        case SimpleConst(c) => Some(info.flag match {
          case SimpleConst(`c`) => true
          case _ => false
        })
        case _ => Some(false)
      }
    }

    private[jc] def matcherIsWeakerThanOutput(info: OutputMoleculeInfo): Option[Boolean] = {
      if (molecule != info.molecule) Some(false)
      else flag match {
        case Wildcard | SimpleVar => Some(true)
        case OtherInputPattern(matcher1) => info.flag match {
          case ConstOutputValue(c) => Some(matcher1.isDefinedAt(c))
          case _ => None // Here we can't reliably determine whether this matcher is weaker.
        }
        case SimpleConst(c) => info.flag match {
          case ConstOutputValue(`c`) => Some(true)
          case ConstOutputValue(_) => Some(false) // definitely not the same constant
          case _ => None // Otherwise, it could be this constant but we can't determine.
        }
        case _ => Some(false)
      }
    }

    // Here "similar" means either it's definitely weaker or it could be weaker (but it is definitely not stronger).
    private[jc] def matcherIsSimilarToOutput(info: OutputMoleculeInfo): Option[Boolean] = {
      if (molecule != info.molecule) Some(false)
      else flag match {
        case Wildcard | SimpleVar => Some(true)
        case OtherInputPattern(matcher1) => info.flag match {
          case ConstOutputValue(c) => Some(matcher1.isDefinedAt(c))
          case _ => Some(true) // Here we can't reliably determine whether this matcher is weaker, but it's similar (i.e. could be weaker).
        }
        case SimpleConst(c) => Some(info.flag match {
          case ConstOutputValue(`c`) => true
          case ConstOutputValue(_) => false // definitely not the same constant
          case _ => true // Otherwise, it could be this constant.
        })
        case UnknownInputPattern => Some(true) // pattern unknown - could be weaker.
      }
    }

    override def toString: String = {
      val printedPattern = flag match {
        case Wildcard => "_"
        case SimpleVar => "."
        case SimpleConst(c) => c.toString
        case OtherInputPattern(_) => s"<${sha1.substring(0, 4)}...>"
        case UnknownInputPattern => s"?"
      }

      s"$molecule($printedPattern)"
    }

  }

  /** Compile-time information about an output molecule pattern in a reaction.
    *
    * @param molecule The molecule injector value that represents the output molecule.
    * @param flag     Type of the output pattern: either a constant value or other value.
    */
  final case class OutputMoleculeInfo(molecule: Molecule, flag: OutputPatternType) {
    override def toString: String = {
      val printedPattern = flag match {
        case ConstOutputValue(()) => ""
        case ConstOutputValue(c) => c.toString
        case OtherOutputPattern => "?"
      }

      s"$molecule($printedPattern)"
    }
  }

  final case class ReactionInfo(inputs: List[InputMoleculeInfo], outputs: Option[List[OutputMoleculeInfo]], hasGuard: GuardPresenceType, sha1: String) {

    // The input pattern sequence is pre-sorted for further use.
    private[jc] val inputsSorted: List[InputMoleculeInfo] = inputs.sortBy { case InputMoleculeInfo(mol, flag, sha) =>
      // wildcard and simplevars are sorted together; more specific matchers must precede less specific matchers
      val patternPrecedence = flag match {
        case Wildcard | SimpleVar => 3
        case OtherInputPattern(_) => 2
        case SimpleConst(_) => 1
        case _ => 0
      }
      (mol.toString, patternPrecedence, sha)
    }

    override def toString: String = s"${inputsSorted.map(_.toString).mkString(" + ")}${hasGuard match {
      case GuardAbsent => ""
      case GuardPresent => " if(...)"
      case GuardPresenceUnknown => " ?"
    }} => ${outputs match {
      case Some(outputMoleculeInfos) => outputMoleculeInfos.map(_.toString).mkString(" + ")
      case None => "?"
    }}"
  }

  // for M[T] molecules, the value inside AbsMolValue[T] is of type T; for B[T,R] molecules, the value is of type
  // ReplyValue[T,R]. For now, we don't use shapeless to enforce this typing relation.
  private[jc] type MoleculeBag = MutableBag[Molecule, AbsMolValue[_]]
  private[jc] type MutableLinearMoleculeBag = mutable.Map[Molecule, AbsMolValue[_]]
  private[jc] type LinearMoleculeBag = Map[Molecule, AbsMolValue[_]]

  private[jc] sealed class ExceptionInJoinRun(message: String) extends Exception(message)
  private[JoinRun] final class ExceptionNoJoinDef(message: String) extends ExceptionInJoinRun(message)
  private[jc] final class ExceptionNoJoinPool(message: String) extends ExceptionInJoinRun(message)
  private[jc] final class ExceptionInjectingSingleton(message: String) extends ExceptionInJoinRun(message)
  private[jc] final class ExceptionNoReactionPool(message: String) extends ExceptionInJoinRun(message)
  private final class ExceptionNoWrapper(message: String) extends ExceptionInJoinRun(message)
  private[jc] final class ExceptionWrongInputs(message: String) extends ExceptionInJoinRun(message)
  private[jc] final class ExceptionEmptyReply(message: String) extends ExceptionInJoinRun(message)
  private[jc] final class ExceptionNoSingleton(message: String) extends ExceptionInJoinRun(message)

  /** Represents a reaction body.
    *
    * @param body Partial function of type {{{ UnapplyArg => Unit }}}
    * @param threadPool Thread pool on which this reaction will be scheduled. (By default, the common pool is used.)
    * @param retry Whether the reaction should be run again when an exception occurs in its body. Default is false.
    */
  final case class Reaction(info: ReactionInfo, body: ReactionBody, threadPool: Option[Pool] = None, retry: Boolean) {

    /** Convenience method to specify thread pools per reaction.
      *
      * Example: run { case a(x) => ... } onThreads threadPool24
      *
      * @param newThreadPool A custom thread pool on which this reaction will be scheduled.
      * @return New reaction value with the thread pool set.
      */
    def onThreads(newThreadPool: Pool): Reaction = Reaction(info, body, Some(newThreadPool), retry)

    /** Convenience method to specify the "retry" option for a reaction.
      *
      * @return New reaction value with the "retry" flag set.
      */
    def withRetry: Reaction = Reaction(info, body, threadPool, retry = true)

    /** Convenience method to specify the "no retry" option for a reaction.
      * (This option is the default.)
      *
      * @return New reaction value with the "retry" flag unset.
      */
    def noRetry: Reaction = Reaction(info, body, threadPool, retry = false)

    /** Convenience method for debugging.
      *
      * @return String representation of input molecules of the reaction.
      */
    override def toString = s"${inputMolecules.map(_.toString).mkString(" + ")} => ...${if (retry)
      "/R" else ""}"

    // Optimization: this is used often.
    val inputMolecules: Seq[Molecule] = info.inputs.map(_.molecule).sortBy(_.toString)
  }

  // Wait until the join definition to which `molecule` is bound becomes quiescent, then inject `callback`.
  // TODO: implement
  def wait_until_quiet[T](molecule: M[T], callback: M[Unit]): Unit = {
    molecule.joinDef match {
      case Some(owner) => owner.setQuiescenceCallback(callback)
      case None => throw new Exception(s"Molecule $molecule is not bound to a join definition")
    }
  }

  /**
    * Convenience syntax: users can write a(x)+b(y) to inject several molecules at once.
    * (However, the molecules are injected one by one in the present implementation.)
    *
    * @param x the first injected molecule
    * @return a class with a + operator
    */
  implicit final class InjectMultiple(x: Unit) {
    def +(n: Unit): Unit = ()
  }

  /**
    * Convenience syntax: users can write a(x)+b(y) in reaction patterns.
    * Pattern-matching can be extended to molecule values as well, for example
    * {{{ { case a(MyCaseClass(x,y)) + b(Some(z)) => ... } }}}
    *
    * @return an unapply operation
    */
  object + {
    def unapply(attr:Any) = Some(attr,attr)
  }

  /** Compute a minimum amount of information about the reaction, without using macros.
    *
    * @param body Body of the reaction. Should not contain any pattern-matching on molecule values, except possibly for the last molecule in the list of input molecules.
    * @return A [[ReactionInfo]] structure that only has information about input molecules.
    */
  private def makeSimpleInfo(body: ReactionBody): ReactionInfo = {
    val inputMoleculesUsed = {
      val moleculesInThisReaction = UnapplyCheckSimple(mutable.MutableList.empty)
      body.isDefinedAt(moleculesInThisReaction)
      val duplicateMolecules = moleculesInThisReaction.inputMolecules diff moleculesInThisReaction.inputMolecules.distinct
      if (duplicateMolecules.nonEmpty) throw new ExceptionInJoinRun(s"Nonlinear pattern: ${duplicateMolecules.mkString(", ")} used twice")
      moleculesInThisReaction.inputMolecules.toList
    }
    ReactionInfo(inputMoleculesUsed.map(m => InputMoleculeInfo(m, UnknownInputPattern, UUID.randomUUID().toString)), None, GuardPresenceUnknown, UUID.randomUUID().toString)
  }

  /** Create a reaction value out of a simple reaction body - no non-wildcard pattern-matching with molecule values except the last one.
    * (The only exception is a pattern match with {{{null}}} or zero values.)
    * The only reason this method exists is for us to be able to write join definitions without any macros.
    * Since this method does not provide a full compile-time analysis of reactions, it should be used only for internal testing and debugging of JoinRun itself.
    * At the moment, this method is used in benchmarks and tests of JoinRun that are run without depending on any macros.
    *
    * @param body Body of the reaction. Should not contain any pattern-matching on molecule values, except possibly for the last molecule in the list of input molecules.
    * @return Reaction value. The [[ReactionInfo]] structure will be filled out in a minimal fashion.
    */
  private[winitzki] def runSimple(body: ReactionBody): Reaction = Reaction(makeSimpleInfo(body), body, retry = false)

  // Container for molecule values
  private[jc] sealed trait AbsMolValue[T] {
    def getValue: T

    override def toString: String = getValue match { case () => ""; case v@_ => v.toString }
  }

  private[jc] final case class MolValue[T](v: T) extends AbsMolValue[T] {
    override def getValue: T = v
  }

  private[jc] final case class BlockingMolValue[T,R](v: T, replyValue: ReplyValue[T,R]) extends AbsMolValue[T] {
    override def getValue: T = v
    // Can we make hash code persistent across mutations with this simple trick?
    private lazy val hashCodeValue: Int = super.hashCode()
    override def hashCode(): Int = hashCodeValue
  }

  // Abstract molecule injector. This type is used in collections of molecules that do not require knowing molecule types.
  abstract sealed class Molecule {
    private[jc] var joinDef: Option[JoinDefinition] = None

    /** The set of reactions that potentially consume this molecule.
      *
      * @return {{{None}}} if the molecule injector is not yet bound to any Join Definition.
      */
    private[jc] def consumingReactions: Option[Set[Reaction]] = joinDef.map(_ => consumingReactionsSet)

    private lazy val consumingReactionsSet: Set[Reaction] = joinDef.get.reactionInfos.keys.filter(_.inputMolecules contains this).toSet

    /** The set of reactions that *potentially* output this molecule.
      * Note that these reactions may be defined in any join definitions, not necessarily in the JD to which this molecule is bound.
      * The set of these reactions may change at run time if new join definitions are written that output this molecule.
      *
      * @return Empty set if the molecule is not yet bound to any join definition.
      */
    private[jc] def injectingReactions: Set[Reaction] = injectingReactionsSet.toSet

    private[jc] val injectingReactionsSet: mutable.Set[Reaction] = mutable.Set()

    /** Check whether the molecule is already bound to a join definition.
      * Note that molecules can be injected only if they are bound.
      *
      * @return True if already bound, false otherwise.
      */
    def isDefined: Boolean = joinDef.nonEmpty

    protected def errorNoJoinDef =
      new ExceptionNoJoinDef(s"Molecule ${this} is not bound to any join definition")

    def setLogLevel(logLevel: Int): Unit =
      joinDef.getOrElse(throw errorNoJoinDef).logLevel = logLevel

    def logSoup: String = joinDef.getOrElse(throw errorNoJoinDef).printBag

    def isBlocking: Boolean

    def isSingleton: Boolean = false
  }

  /** Non-blocking molecule class. Instance is mutable until the molecule is bound to a join definition.
    *
    * @param name Name of the molecule, used for debugging only.
    * @tparam T Type of the value carried by the molecule.
    */
  final class M[T: ClassTag](val name: String) extends Molecule with Function1[T, Unit] {
    /** Inject a non-blocking molecule.
      *
      * @param v Value to be put onto the injected molecule.
      */
    def apply(v: T): Unit = joinDef.getOrElse(throw errorNoJoinDef).inject[T](this, MolValue(v))

    override def toString: String = name

    def unapply(arg: UnapplyArg): Option[T] = arg match {

    case UnapplyCheckSimple(inputMoleculesProbe) =>   // used only by runSimple
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

    def value: T = joinDef.map(_.getVolatileValue(this)).getOrElse(throw errorNoJoinDef)

    private[jc] var isSingletonBoolean = false

    override def isSingleton: Boolean = isSingletonBoolean

    override def isBlocking = false
  }

  /** Reply-value wrapper for blocking molecules. This is a mutable class.
    *
    * @param molecule The blocking molecule whose reply value this wrapper represents.
    * @param result Reply value as {{{Option[R]}}}. Initially this is None, and it may be assigned at most once by the
    *               "reply" action if the reply is "valid" (i.e. not timed out).
    * @param semaphore Mutable semaphore reference. This is initialized only once when creating an instance of this
    *                  class. The semaphore will be acquired when injecting the molecule and released by the "reply"
    *                  action.
    * @param errorMessage Optional error message, to notify the caller or to raise an exception when the user made a
    *                     mistake in using the molecule.
    * @param replyInvalid Will be set to "true" if the molecule received a reply more than once, or if timeout expired.
    * @tparam R Type of the value replied to the caller via the "reply" action.
    */
  private[jc] final case class ReplyValue[T,R](
    molecule: B[T,R],
    var result: Option[R] = None,
    private var semaphore: Semaphore = { val s = new Semaphore(0, true); s.drainPermits(); s },
    var errorMessage: Option[String] = None,
    var replyInvalid: Boolean = false
  ) {
    def releaseSemaphore(): Unit = if (semaphore != null) semaphore.release()

    def acquireSemaphore(timeoutNanos: Option[Long] = None): Boolean =
      if (semaphore != null)
        timeoutNanos match {
          case Some(nanos) => semaphore.tryAcquire(nanos, TimeUnit.NANOSECONDS)
          case None => semaphore.acquire(); true
        }
      else false

    def deleteSemaphore(): Unit = {
      releaseSemaphore()
      semaphore = null
    }

    def apply(x: R): Boolean = {
      if (replyInvalid || result.nonEmpty) {
        replyInvalid = true // We do not reassign the reply value. Error will be reported.
      } else {
        result = Some(x)
      }
      !replyInvalid
    }
  }

  /** Blocking molecule class. Instance is mutable until the molecule is bound to a join definition.
    *
    * @param name Name of the molecule, used for debugging only.
    * @tparam T Type of the value carried by the molecule.
    * @tparam R Type of the value replied to the caller via the "reply" action.
    */
  final class B[T: ClassTag, R](val name: String) extends Molecule with Function1[T, R] {

    /** Inject a blocking molecule and receive a value when the reply action is performed.
      *
      * @param v Value to be put onto the injected molecule.
      * @return The "reply" value.
      */
    def apply(v: T): R =
      joinDef.map(_.injectAndReply[T,R](this, v, ReplyValue[T,R](molecule = this)))
        .getOrElse(throw new ExceptionNoJoinDef(s"Molecule $this is not bound to any join definition"))

    /** Inject a blocking molecule and receive a value when the reply action is performed, unless a timeout is reached.
      *
      * @param timeout Timeout in any time interval.
      * @param v Value to be put onto the injected molecule.
      * @return Non-empty option if the reply was received; None on timeout.
      */
    def apply(timeout: Duration)(v: T): Option[R] =
      joinDef.map(_.injectAndReplyWithTimeout[T,R](timeout.toNanos, this, v, ReplyValue[T,R](molecule = this)))
        .getOrElse(throw new ExceptionNoJoinDef(s"Molecule $this is not bound to any join definition"))

    override def toString: String = name + "/B"

    def unapply(arg: UnapplyArg): Option[(T, ReplyValue[T,R])] = arg match {

      case UnapplyCheckSimple(inputMoleculesProbe) =>   // used only by runSimple
        inputMoleculesProbe += this
        Some((null, null).asInstanceOf[(T, ReplyValue[T,R])]) // hack for testing only. This value will not be used.

      // This is used just before running the actual reactions, to determine which ones pass all the pattern-matching tests.
      // We also gather the information about the molecule values actually used by the reaction, in case the reaction can start.
      case UnapplyRunCheck(moleculeValues, usedInputs) =>
        for {
          v <- moleculeValues.getOne(this)
        } yield {
          usedInputs += (this -> v)
          (v.getValue, null).asInstanceOf[(T, ReplyValue[T,R])]
        }

      // This is used when running the chosen reaction.
      case UnapplyRun(moleculeValues) => moleculeValues.get(this).map {
        case BlockingMolValue(v, srv) => (v, srv).asInstanceOf[(T, ReplyValue[T,R])]
        case m@_ =>
          throw new ExceptionNoWrapper(s"Internal error: molecule $this with no value wrapper around value $m")
      }
    }

    override def isBlocking = true
  }

  val defaultJoinPool = new FixedPool(2)
  val defaultReactionPool = new FixedPool(4)

  private[jc] sealed trait UnapplyArg // The disjoint union type for arguments passed to the unapply methods.
  private final case class UnapplyCheckSimple(inputMolecules: mutable.MutableList[Molecule]) extends UnapplyArg // used only for `runSimple` and in tests
  private[jc] final case class UnapplyRunCheck(moleculeValues: MoleculeBag, usedInputs: MutableLinearMoleculeBag) extends UnapplyArg // used for checking that reaction values pass the pattern-matching, before running the reaction
  private[jc] final case class UnapplyRun(moleculeValues: LinearMoleculeBag) extends UnapplyArg // used for running the reaction

  /** Type alias for reaction body.
    *
    */
  private[jc] type ReactionBody = PartialFunction[UnapplyArg, Unit]

  def join(rs: Reaction*): WarningsAndErrors = join(defaultReactionPool, defaultJoinPool)(rs: _*)
  def join(reactionPool: Pool)(rs: Reaction*): WarningsAndErrors = join(reactionPool, reactionPool)(rs: _*)

  /** Create a join definition with one or more reactions.
    * All input and output molecules in reactions used in this JD should have been
    * already defined, and input molecules should not be already bound to another JD.
    *
    * @param rs One or more reactions of type [[JoinRun#Reaction]]
    * @param reactionPool Thread pool for running new reactions.
    * @param joinPool Thread pool for use when making decisions to schedule reactions.
    * @return List of warning messages.
    */
  def join(reactionPool: Pool, joinPool: Pool)(rs: Reaction*): WarningsAndErrors = {

    // Create a join definition object holding the given local chemistry.
    // The constructor of JoinDefinition will perform static analysis of all given reactions.
    new JoinDefinition(rs, reactionPool, joinPool).diagnostics

  }

}
