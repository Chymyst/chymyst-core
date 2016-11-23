package code.winitzki.jc

/*
Join Calculus (JC) is a micro-framework for declarative concurrency.

JC is basically “Actors” made type-safe, stateless, and more high-level.

The code is inspired by previous implementations by He Jiansen (https://github.com/Jiansen/ScalaJoin, 2011)
and Philipp Haller (http://lampwww.epfl.ch/~phaller/joins/index.html, 2008).

TODO and roadmap:
  value * difficulty - description
 4 * 2 - make thread pools an Option, so that default thread pool can be used for all reactions except some. Do not use implicit arguments - use default arguments.

 4 * 2 - make helper functions to create a new joinpool

 2 * 2 - unify join pools and reaction pools into one type; replace implicits by default argument values.

 2 * 2 - should `run` take ReactionBody or simply UnapplyArg => Unit?

 3 * 1 - make helper functions to create new single-thread pools using a given thread or a given executor/handler

 4 * 2 - make helper functions to create an actor-based pool, or a pool with autoresizing - then can use "blocking(_)", otherwise the fixed thread pool will do nothing about "blocking(_)".

 5 * 5 - create and use an RDLL (random doubly linked list) data structure for storing molecule values; benchmark. Or use Vector with tail-swapping?

 2 * 2 - perhaps use separate molecule bags for molecules with unit value and with non-unit value? for Booleans? for blocking and non-blocking?

 5 * 5 - implement fairness with respect to molecules
 * - go through possible values when matching (can do?) Important: can get stuck when molecules are in different order. Or need to shuffle.

 5 * 5 - allow unrestricted pattern-matching in reactions
 - completely fix the problem with pattern-matching not at the end of input molecule list.
  Probably will need a macro. At the moment, we can have some pattern-matching but it's not correct.

 4 * 5 - do not schedule reactions if queues are full. At the moment, RejectedExecutionException is thrown. It's best to avoid this. Molecules should be accumulated in the bag, to be inspected at a later time (e.g. when some tasks are finished). Insert a call at the end of each reaction, to re-inspect the bag.

 3 * 3 - define a special "switch off" or "quiescence" molecule - per-join, with a callback parameter.
 Also define a "shut down" molecule which will enforce quiescence and then shut down the join pool and the reaction pool.

 4 * 5 - implement distributed execution by sharing the join pool with another machine (but running the join definitions only on the master node)

 2 * 2 - benchmark and profile the performance of blocking molecules (make many reactions that block and unblock)

 3 * 4 - LAZY values on molecules? By default? What about pattern-matching then? Probably need to refactor SyncMol and AsyncMol into non-case classes and change some other logic.

 3 * 5 - Can we implement JoinRun using Future / Promise and remove all blocking and all semaphores?

 5 * 5 - try to inspect the reaction body using a macro. Can we match on q"{ case a(_) + ... => ... }"?
 Can we return the list of input molecules and other info - e.g. whether the pattern-match
 is nontrivial in this molecule, whether blocking molecules have a reply matcher specified,
 whether the reply pseudo-molecule is being used in the body, whether all other output molecules are already defined.
 If this is possible, define an alternative "join" or "run" helper functions in the Macros package.
  Can we do some reasoning about reactions at compile time or at runtime but before starting any reactions?

 2 * 3 - understand the "reader-writer" example

 3 * 2 - add per-molecule logging; log to file or to logger function

 4 * 5 - implement multiple injection construction a+b+c so that a+b-> and b+c-> reactions are equally likely to start.
 
 4 * 5 - allow several reactions to be scheduled simultaneously out of the same join definition, when this is possible. Avoid locking the entire bag? - perhaps, partition it, based on join definition information gleaned using a macro.

 3 * 3 - make "reply actions" before the reaction finishes, not after. Revise error reporting (on double use) accordingly.

 5 * 5 - implement "progress and safety" assertions so that we could prevent deadlock in more cases
 and be able to better reason about our declarative reactions.

 2 * 4 - allow molecule values to be parameterized types or even higher-kinded types?

 2 * 2 - make memory profiling / benchmarking; how many molecules can we have per 1 GB of RAM?

 3 * 3 - figure out whether we are replying to the blocking molecules only at the end of reaction, or also during the reaction (this is preferable)

 3 * 4 - implement nonlinear input patterns

 2 * 2 - annotate join pools with names. Make a macro for auto-naming join pools of various kinds.

 2 * 2 - add test for Pool such that we submit a closure that sleeps and then submit another closure. Should get / or not get the RejectedExecutionException
  * */

import DefaultValue.defaultValue
import java.util.concurrent.{Semaphore, TimeUnit}

import scala.collection.mutable
import scala.reflect.ClassTag

object JoinRun {

  /**
    * Type alias, for the type of a non-blocking molecule injector.
    *
    * @tparam T the type of value carried by the corresponding molecule
    */
  type JA[T] = AsynMol[T]

  /**
    * Type alias, for the type of a blocking molecule injector.
    *
    * @tparam T the type of value carried by the corresponding molecule
    * @tparam R the type of value received back from blocking call
    */
  type JS[T,R] = SynMol[T,R]

  /**
    * Convenience function: declares a new non-blocking molecule injector.
    *
    * @tparam T the type of elements of the collection
    * @return a new non-blocking molecule injector with no name
    */
  def ja[T: ClassTag] = new AsynMol[T](None)
  /**
    * Convenience function: declares a new blocking molecule injector.
    *
    * @tparam T the type of elements of the collection
    * @tparam R the type of value received back from blocking call
    * @return a new blocking molecule injector with no name
    */
  def js[T: ClassTag,R] = new SynMol[T,R](None)
  /**
    * Convenience function: declares a new non-blocking molecule injector.
    *
    * @param name the name of the molecule (used for debugging only)
    * @tparam T the type of elements of the collection
    * @return a new non-blocking molecule injector with specified name
    */
  def ja[T: ClassTag](name: String) = new AsynMol[T](Some(name))
  /**
    * Convenience function: declares a new non-blocking molecule injector.
    *
    * @param name the name of the molecule (used for debugging only)
    * @tparam T the type of elements of the collection
    * @tparam R the type of value received back from blocking call
    * @return a new blocking molecule injector with specified name
    */
  def js[T: ClassTag,R](name: String) = new SynMol[T,R](Some(name))

  // Wait until the join definition to which `molecule` belongs becomes quiescent, then inject `callback`.
  // TODO: implement
  def wait_until_quiet[T](molecule: AsynMol[T], callback: AsynMol[Unit]): Unit = {
    molecule.joinDef match {
      case Some(owner) => owner.setQuiescenceCallback(callback)
      case None => throw new Exception(s"Molecule $molecule belongs to no join definition")
    }
  }

  /**
    * Convenience syntax: users can write a(x)+b(y) to inject several molecules at once.
    * (However, the molecules are injected one by one in the present implementation.)
    *
    * @param x the first injected molecule
    * @return a class with a + operator
    */
  implicit final class JoinableUnit(x: Unit) {
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

  /**
    * Users will define reactions using this function.
    * Examples: {{{ run { a(_) => ... } }}}
    * {{{ run { a (_) => ...} onThreads jPool }}}
    *
    * @param body The body of the reaction. This must be a partial function with pattern-matching on molecules.
    * @return A reaction value, to be used later in [[JoinRun#join]].
    */
  def run(body: ReactionBody): Reaction = Reaction(body)

  //
  /**
    * This is an alias for [[JoinRun#run]], to be used in case [[JoinRun#run]] clashes
    * with another name imported into the local scope (e.g. in  scalatest).
    * Examples: & { a(_) => ... }
    * & { a (_) => ...} onThreads jPool
    */
  object & {
    // Users will create reactions using these functions.
    def apply(body: ReactionBody): Reaction = Reaction(body)
  }

  // Container for molecule values
  private sealed trait AbsMolValue[T] {
    def getValue: T

    override def toString: String = getValue match { case () => ""; case v@_ => v.toString }
  }

  private final case class AsyncMolValue[T](v: T) extends AbsMolValue[T] {
    override def getValue: T = v
  }

  private final case class SyncMolValue[T,R](v: T, syncReplyValue: SyncReplyValue[R]) extends AbsMolValue[T] {
    override def getValue: T = v
  }

  private sealed class ExceptionInJoinRun(message: String) extends Exception(message)
  private[JoinRun] final class ExceptionNoJoinDef(message: String) extends ExceptionInJoinRun(message)
  private final class ExceptionNoJoinPool(message: String) extends ExceptionInJoinRun(message)
  private final class ExceptionNoReactionPool(message: String) extends ExceptionInJoinRun(message)
  private final class ExceptionNoWrapper(message: String) extends ExceptionInJoinRun(message)
  private final class ExceptionWrongInputs(message: String) extends ExceptionInJoinRun(message)
  private final class ExceptionEmptyReply(message: String) extends ExceptionInJoinRun(message)

  // Abstract molecule injector. This type is used in collections of molecules that do not require knowing molecule types.
  abstract sealed class AbsMol(name: Option[String]) {
    private[JoinRun] var joinDef: Option[JoinDefinition] = None

    def getName: String = name.getOrElse(super.toString)

    protected def errorNoJoinDef =
      new ExceptionNoJoinDef(s"Molecule ${this} does not belong to any join definition")

    def setLogLevel(logLevel: Int): Unit =
      joinDef.map(o => o.logLevel = logLevel).getOrElse(throw errorNoJoinDef)

    def logSoup: String = joinDef.map(o => o.printBag).getOrElse(throw errorNoJoinDef)
  }

  /** Non-blocking molecule class. Instance is mutable until the molecule is bound to a join definition.
    *
    * @param name Optional name of the molecule, used for debugging only.
    * @tparam T Type of the value carried by the molecule.
    */
  private[JoinRun] final class AsynMol[T: ClassTag](name: Option[String] = None) extends AbsMol(name) with Function1[T, Unit] {
    /** Inject a non-blocking molecule.
      *
      * @param v Value to be put onto the injected molecule.
      */
    def apply(v: T): Unit = joinDef.map(_.injectAsync[T](this, AsyncMolValue(v))).getOrElse(throw errorNoJoinDef)

    override def toString: String = getName

    def unapply(arg: UnapplyArg): Option[T] = arg match {
      // When we are gathering information about the input molecules, `unapply` will always return Some(...),
      // so that any pattern-matching on arguments will continue with null (since, at this point, we have no values).
      // Any pattern-matching will work unless null fails.
      case UnapplyCheck(inputMoleculesProbe) =>
        if (inputMoleculesProbe contains this) {
          throw new Exception(s"Nonlinear pattern: ${this} used twice")
        }
        else {
          inputMoleculesProbe.add(this)
        }
        Some(defaultValue[T]) // hack. This value will not be used.

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
        .map(_.asInstanceOf[AsyncMolValue[T]].getValue)
    }
  }

  /** Reply-value wrapper for synchronous molecules. This is a mutable class.
    *
    * @param result Reply value as {{{Option[R]}}}. Initially this is None, and it may be assigned at most once by the
    *               "reply" action.
    * @param semaphore Mutable semaphore reference. This is initialized only once when creating an instance of this
    *                  class. The semaphore will be acquired when injecting the molecule and released by the "reply"
    *                  action.
    * @param errorMessage Optional error message, to notify the caller or to raise an exception when the user made a
    *                     mistake in using the molecule.
    * @param repliedTwice Will be set to "true" if the molecule received a reply more than once.
    * @tparam R Type of the value replied to the caller via the "reply" action.
    */
  private[JoinRun] final case class SyncReplyValue[R](
    var result: Option[R] = None,
    private var semaphore: Semaphore = { val s = new Semaphore(0, true); s.drainPermits(); s },
    var errorMessage: Option[String] = None,
    var repliedTwice: Boolean = false
  ) {
    def releaseSemaphore() = if (semaphore != null) semaphore.release()

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

    def apply(x: R): Unit = {
      if (result.nonEmpty)
        repliedTwice = true // We do not reassign the reply value. Error will be reported.
      else {
        result = Some(x)
      }
    }
  }

  /** Blocking molecule class. Instance is mutable until the molecule is bound to a join definition.
    *
    * @param name Optional name of the molecule, used for debugging only.
    * @tparam T Type of the value carried by the molecule.
    * @tparam R Type of the value replied to the caller via the "reply" action.
    */
  private[JoinRun] final class SynMol[T: ClassTag, R](name: Option[String] = None) extends AbsMol(name) with Function1[T, R] {

    /** Inject a blocking molecule and receive a value when the reply action is performed.
      *
      * @param v Value to be put onto the injected molecule.
      * @return The "reply" value.
      */
    def apply(v: T): R =
      joinDef.map(_.injectSyncAndReply[T,R](this, v, SyncReplyValue[R]()))
        .getOrElse(throw new ExceptionNoJoinDef(s"Molecule $this does not belong to any join definition"))

    /** Inject a blocking molecule and receive a value when the reply action is performed, unless a timeout is reached.
      *
      * @param timeout Timeout in nanoseconds.
      * @param v Value to be put onto the injected molecule.
      * @return Non-empty option if the reply was received; None on timeout.
      */
    def apply(timeout: Long)(v: T): Option[R] =
      joinDef.map(_.injectSyncAndReplyWithTimeout[T,R](timeout, this, v, SyncReplyValue[R]()))
        .getOrElse(throw new ExceptionNoJoinDef(s"Molecule $this does not belong to any join definition"))

    override def toString: String = getName + "/S"

    def unapply(arg: UnapplyArg): Option[(T, SyncReplyValue[R])] = arg match {
      // When we are gathering information about the input molecules, `unapply` will always return Some(...),
      // so that any pattern-matching on arguments will continue with null (since, at this point, we have no values).
      // Any pattern-matching will work unless null fails.
      case UnapplyCheck(inputMoleculesProbe) =>
        if (inputMoleculesProbe contains this) {
          throw new Exception(s"Nonlinear pattern: ${this} used twice")
        }
        else
          inputMoleculesProbe.add(this)

        Some((defaultValue[T], null).asInstanceOf[(T, SyncReplyValue[R])]) // hack. This value will not be used.

      // This is used just before running the actual reactions, to determine which ones pass all the pattern-matching tests.
      // We also gather the information about the molecule values actually used by the reaction, in case the reaction can start.
      case UnapplyRunCheck(moleculeValues, usedInputs) =>
        for {
          v <- moleculeValues.getOne(this)
        } yield {
          usedInputs += (this -> v)
          (v.getValue, null).asInstanceOf[(T, SyncReplyValue[R])]
        }

      // This is used when running the chosen reaction.
      case UnapplyRun(moleculeValues) => moleculeValues.get(this).map {
        case SyncMolValue(v, srv) => (v, srv).asInstanceOf[(T, SyncReplyValue[R])]
        case m@_ =>
          throw new ExceptionNoWrapper(s"Internal error: molecule $this with no synchronous value wrapper around value $m")
      }
    }
  }

  val defaultJoinPool = new FixedPool(2)
  val defaultReactionPool = new FixedPool(4)

  private[jc] sealed trait UnapplyArg // The disjoint union type for arguments passed to the unapply methods.
  private final case class UnapplyCheck(inputMolecules: mutable.Set[AbsMol]) extends UnapplyArg
  private final case class UnapplyRunCheck(moleculeValues: MoleculeBag, usedInputs: MutableLinearMoleculeBag) extends UnapplyArg
  private final case class UnapplyRun(moleculeValues: LinearMoleculeBag) extends UnapplyArg

  /** Type alias for reaction body.
    *
    */
  private[jc] type ReactionBody = PartialFunction[UnapplyArg, Unit]

  /** Represents a reaction body.
    *
    * @param body Partial function of type {{{ UnapplyArg => Unit }}}
    * @param threadPool Thread pool on which this reaction will be scheduled. (By default, the common pool is used.)
    * @param retry Whether the reaction should be run again when an exception occurs in its body. Default is false.
    */
  private[jc] final case class Reaction(body: ReactionBody, threadPool: Option[Pool] = None, retry: Boolean = false) {
    lazy val inputMoleculesUsed: Set[AbsMol] = {
      val moleculesInThisReaction = UnapplyCheck(mutable.Set.empty)
      body.isDefinedAt(moleculesInThisReaction)
      moleculesInThisReaction.inputMolecules.toSet
    }

    /** Convenience method to specify thread pools per reaction.
      *
      * Example: run { case a(x) => ... } onThreads threadPool24
      *
      * @param newThreadPool A custom thread pool on which this reaction will be scheduled.
      * @return New reaction value with the thread pool set.
      */
    def onThreads(newThreadPool: Pool): Reaction = Reaction(body, Some(newThreadPool), retry)

    /** Convenience method to specify the "retry" option for a reaction.
      *
      * @return New reaction value with the "retry" flag set.
      */
    def withRetry: Reaction = Reaction(body, threadPool, retry = true)

    /** Convenience method to specify the "no retry" option for a reaction.
      * (This option is the default.)
      *
      * @return New reaction value with the "retry" flag unset.
      */
    def noRetry: Reaction = Reaction(body, threadPool, retry = false)

    /** Convenience method for debugging.
      *
      * @return String representation of input molecules of the reaction.
      */
    override def toString = s"${inputMoleculesUsed.toSeq.map(_.toString).sorted.mkString(" + ")} => ...${if (retry)
      "/R" else ""}"
  }

  def join(rs: Reaction*): Unit = join(defaultJoinPool, defaultReactionPool)(rs: _*)
  def join(joinPool: Pool)(rs: Reaction*): Unit = join(joinPool, defaultReactionPool)(rs: _*)

  /** Create a join definition with one or more reactions.
    * All input and output molecules in reactions used in this JD should have been
    * already defined, and input molecules should not already belong to another JD.
    *
    * @param rs One or more reactions of type [[JoinRun#Reaction]]
    * @param reactionPool Thread pool for running new reactions.
    * @param joinPool Thread pool for use when making decisions to schedule reactions.
    */
  def join(joinPool: Pool, reactionPool: Pool)(rs: Reaction*): Unit = {

    val knownMolecules : Map[Reaction, Set[AbsMol]] = rs.map { r => (r, r.inputMoleculesUsed) }.toMap

    // create a join definition object holding the given reactions and inputs
    val join = new JoinDefinition(knownMolecules, reactionPool, joinPool)

    // set the owner on all input molecules in this join definition
    knownMolecules.values.toSet.flatten.foreach { m =>
      m.joinDef match {
        case Some(owner) => throw new Exception(s"Molecule $m cannot be used as input since it was already used in $owner")
        case None => m.joinDef = Some(join)
      }
    }

  }

  private implicit final class ShufflableSeq[T](a: Seq[T]) {
    def shuffle: Seq[T] = scala.util.Random.shuffle(a)
  }

  // for JA[T] molecules, the value inside AbsMolValue[T] is of type T; for JS[T,R] molecules, the value is of type
  // SyncReplyValue[T,R]
  private type MoleculeBag = MutableBag[AbsMol, AbsMolValue[_]]
  private type MutableLinearMoleculeBag = mutable.Map[AbsMol, AbsMolValue[_]]
  private type LinearMoleculeBag = Map[AbsMol, AbsMolValue[_]]

  /** Represents the join definition, which holds one or more reaction definitions.
    * At run time, the join definition maintains a bag of currently available molecules
    * and runs reactions.
    * The user will never see any instances of this class.
    *
    * @param inputMolecules The molecules known to be inputs of the given reactions.
    * @param reactionPool The thread pool on which reactions will be scheduled.
    * @param joinPool The thread pool on which the join definition will decide reactions and manage the molecule bag.
    */
  private[JoinRun] final class JoinDefinition(val inputMolecules: Map[Reaction, Set[AbsMol]],
    var reactionPool: Pool, var joinPool: Pool) {

    private val quiescenceCallbacks: mutable.Set[AsynMol[Unit]] = mutable.Set.empty

    override def toString = s"Join{${inputMolecules.keys.mkString("; ")}}"

    var logLevel = 0

    def printBag: String = {
      val moleculesPrettyPrinted = if (moleculesPresent.size > 0) s"Molecules: ${moleculeBagToString(moleculesPresent)}" else "No molecules"

      s"${this.toString}\n$moleculesPrettyPrinted"
    }

    def setQuiescenceCallback(callback: AsynMol[Unit]): Unit = {
      quiescenceCallbacks.add(callback)
    }

    private lazy val possibleReactions: Map[AbsMol, Seq[Reaction]] = inputMolecules.toSeq
      .flatMap { case (r, ms) => ms.toSeq.map { m => (m, r) } }
      .groupBy { case (m, r) => m }
      .map { case (m, rs) => (m, rs.map(_._2)) }

    // Initially, there are no molecules present.
    private val moleculesPresent: MoleculeBag = new MutableBag[AbsMol, AbsMolValue[_]]

    private def moleculeBagToString(mb: MoleculeBag): String =
      mb.getMap.toSeq
        .map{ case (m, vs) => (m.toString, vs) }
        .sortBy(_._1)
        .flatMap {
        case (m, vs) => vs.map {
          case (mv, 1) => s"$m($mv)"
          case (mv, i) => s"$m($mv) * $i"
        }
      }.mkString(", ")

    private def moleculeBagToString(mb: LinearMoleculeBag): String =
      mb.map {
        case (m, jmv) => s"$m($jmv)"
      }.mkString(", ")

    private def moleculeBagToString(mb: MutableLinearMoleculeBag): String =
      mb.map {
        case (m, jmv) => s"$m($jmv)"
      }.mkString(", ")

    // Adding an asynchronous molecule may trigger at most one reaction.
    def injectAsync[T](m: AbsMol, jmv: AbsMolValue[T]): Unit = {
      if (joinPool.isInactive) throw new ExceptionNoJoinPool(s"In $this: Cannot inject molecule $m since join pool is not active")
      else if (!Thread.currentThread().isInterrupted) joinPool.runClosure ({
        val (reaction, usedInputs: LinearMoleculeBag) = synchronized {
          moleculesPresent.addToBag(m, jmv)
          if (logLevel > 0) println(s"Debug: $this injecting $m($jmv) on thread pool $joinPool, now have molecules ${moleculeBagToString(moleculesPresent)}")
          val usedInputs: MutableLinearMoleculeBag = mutable.Map.empty
          val reaction = possibleReactions.get(m)
            .flatMap(_.shuffle.find(r => {
              usedInputs.clear()
              r.body.isDefinedAt(UnapplyRunCheck(moleculesPresent, usedInputs))
            }))
          reaction.foreach(_ => moleculesPresent.removeFromBag(usedInputs))
          (reaction, usedInputs.toMap)
        } // End of synchronized block.

        // We are just starting a reaction, so we don't need to hold the thread any more.
        reaction match {
          case Some(r) =>
            if (logLevel > 1) println(s"Debug: In $this: starting reaction {$r} on thread pool ${r.threadPool} while on thread pool $joinPool with inputs ${moleculeBagToString(usedInputs)}")
            if (logLevel > 2) println(
              if (moleculesPresent.size == 0)
                s"Debug: In $this: no molecules remaining"
              else
                s"Debug: In $this: remaining molecules ${moleculeBagToString(moleculesPresent)}"
            )
            // A basic check that we are using our mutable structures safely. We should never see this error.
            if (!r.inputMoleculesUsed.equals(usedInputs.keys.toSet)) {
              val message = s"Internal error: In $this: attempt to start reaction {$r} with incorrect inputs ${moleculeBagToString(usedInputs)}"
              println(message)
              throw new ExceptionWrongInputs(message)
            }
            // Build a closure out of the reaction, and run that closure on the reaction's thread pool.
            val poolForReaction = r.threadPool.getOrElse(reactionPool)
            if (poolForReaction.isInactive) throw new ExceptionNoReactionPool(s"In $this: cannot run reaction $r since reaction pool is not active")
            else if (!Thread.currentThread().isInterrupted) poolForReaction.runClosure( {
              if (logLevel > 1) println(s"Debug: In $this: reaction {$r} started on thread pool $joinPool with thread id ${Thread.currentThread().getId}")
              try {
                // Here we actually apply the reaction body to its input molecules.
                r.body.apply(UnapplyRun(usedInputs))
              } catch {
                case e: ExceptionInJoinRun =>
                  // Running the reaction body produced an exception that is internal to JoinRun.
                  // We should not try to recover from this; it is most either an error on user's part
                  // or a bug in JoinRun.
                  println(s"In $this: Reaction {$r} produced an exception that is internal to JoinRun. Input molecules ${moleculeBagToString(usedInputs)} were not injected again. Exception trace will be printed now.")
                  e.printStackTrace() // This will be printed asynchronously, out of order with the previous message.
                  throw e

                case e: Exception =>
                  // Running the reaction body produced an exception. Note that the exception has killed a thread.
                  // We will now re-insert the input molecules. Hopefully, no side-effects or output molecules were produced so far.
                  val aboutMolecules = if (r.retry) {
                    usedInputs.foreach { case (mol, v) => injectAsync(mol, v) }
                    "were injected again"
                  }
                  else "were consumed and not injected again"

                  println(s"In $this: Reaction {$r} produced an exception. Input molecules ${moleculeBagToString(usedInputs)} $aboutMolecules. Exception trace will be printed now.")
                  e.printStackTrace() // This will be printed asynchronously, out of order with the previous message.
              }
              // For any blocking input molecules that have no reply, put an error message into them and reply with empty
              // value to unblock the threads.

              def nonemptyOpt[S](s: Seq[S]): Option[Seq[S]] = if (s.isEmpty) None else Some(s)

              // Compute error messages here in case we will need them later.
              val blockingMoleculesWithNoReply = nonemptyOpt(usedInputs
                .filter { case (_, SyncMolValue(_, syncReplyValue)) => syncReplyValue.result.isEmpty; case _ => false }
                .keys.toSeq).map(_.map(_.toString).sorted.mkString(", "))

              val messageNoReply = blockingMoleculesWithNoReply map { s => s"Error: In $this: Reaction {$r} finished without replying to $s" }

              val blockingMoleculesWithMultipleReply = nonemptyOpt(usedInputs
                .filter { case (_, SyncMolValue(_, syncReplyValue)) => syncReplyValue.repliedTwice; case _ => false }
                .keys.toSeq).map(_.map(_.toString).sorted.mkString(", "))

              val messageMultipleReply = blockingMoleculesWithMultipleReply map { s => s"Error: In $this: Reaction {$r} replied to $s more than once" }

              // We will report all errors to each synchronous molecule.
              val errorMessage = Seq(messageNoReply, messageMultipleReply).flatten.mkString("; ")
              val haveError = blockingMoleculesWithNoReply.nonEmpty || blockingMoleculesWithMultipleReply.nonEmpty

              // Insert error messages into synchronous reply wrappers and release all semaphores.
              usedInputs.foreach {
                case (_, SyncMolValue(_, syncReplyValue)) =>
                  if (haveError) {
                    syncReplyValue.errorMessage = Some(errorMessage)
                  }
                  syncReplyValue.releaseSemaphore()

                case _ => ()
              }

              if (haveError) {
                println(errorMessage)
                throw new Exception(errorMessage)
              }
            }, name = Some(s"[Reaction $r in $this]"))

          case None =>
            if (logLevel > 2) println(s"Debug: In $this: no reactions started")
            ()

        }

      }, name = Some(s"[Injecting $m($jmv) in $this]"))
    }


    // Adding a synchronous molecule may trigger at most one reaction and must return a value of type R.
    // We must make this a blocking call, so we acquire a semaphore (with timeout).
    def injectSyncAndReply[T,R](m: SynMol[T,R], v: T, valueWithResult: SyncReplyValue[R]): R = {
      injectAsync(m, SyncMolValue(v, valueWithResult))
//      try  // not sure we need this.
        valueWithResult.acquireSemaphore()
//      catch {
//        case e: InterruptedException => e.printStackTrace()
//      }
      valueWithResult.deleteSemaphore() // make sure it's gone

      // check if we had any errors, and that we have a result value
      valueWithResult.errorMessage match {
        case Some(message) => throw new Exception(message)
        case None => valueWithResult.result.getOrElse(
          throw new ExceptionEmptyReply(s"Internal error: In $this: $m received an empty reply without an error message"
          )
        )
      }
    }

    def injectSyncAndReplyWithTimeout[T,R](timeout: Long, m: SynMol[T,R], v: T, valueWithResult: SyncReplyValue[R]):
    Option[R] = {
      injectAsync(m, SyncMolValue(v, valueWithResult))
      //      try  // not sure we need this.
      val success = valueWithResult.acquireSemaphore(Some(timeout))
      //      catch {
      //        case e: InterruptedException => e.printStackTrace()
      //      }
      valueWithResult.deleteSemaphore() // make sure it's gone

      // check if we had any errors, and that we have a result value
      valueWithResult.errorMessage match {
        case Some(message) => throw new Exception(message)
        case None => if (success) Some(valueWithResult.result.getOrElse(
            throw new ExceptionEmptyReply(s"Internal error: In $this: $m received an empty reply without an error message"))
        ) else None

      }
    }

  }

}
