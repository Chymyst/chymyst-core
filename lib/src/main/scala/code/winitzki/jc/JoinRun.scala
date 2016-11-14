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

 3 * 1 - make helper functions to create new single-thread pools using a given thread or a given executor/handler

 4 * 2 - make helper functions to create an actor-based pool

 5 * 5 - create and use an RDLL (random doubly linked list) data structure for storing molecule values; benchmark. Or use Vector with tail-swapping?

 5 * 5 - implement fairness with respect to molecules
 * - go through possible values when matching (can do?) Important: can get stuck when molecules are in different order. Or need to shuffle.

 5 * 5 - allow unrestricted pattern-matching in reactions

 - completely fix the problem with pattern-matching not at the end of input molecule list.
  Probably will need a macro. At the moment, we can have some pattern-matching but it's not correct.

 3 * 3 - define a special "switch off" or "quiescence" molecule - per-join, with a callback parameter

 4 * 5 - implement disjoin by sharing the join pool with another machine (but running the join definitions only on the master node)

 3 * 4 - LAZY values on molecules? By default? What about pattern-matching then? Probably need to refactor SyncMol and AsyncMol into non-case classes and change some other logic.

 2 * 1 - make AbsMolValue into parameterized class and get rid of Any in MolValue and its derived classes?
 
 2 * 2 - try to avoid case class matching in favor of overloading methods on case classes (possible performance benefit)

 4 * 3 - add javadoc for the library

 5 * 5 - try to inspect the reaction body using a macro. Can we match on q"{ case a(_) + ... => ... }"?
 Can we return the list of input molecules and other info - e.g. whether the pattern-match
 is nontrivial in this molecule, whether sync. molecultes have a reply matcher specified,
 whether the reply molecule is being used in the body, whether all other output molecules are already defined.
 If this is possible, define an alternative "join" or "run" helper functions in the Macros package. 

 2 * 3 - understand the "reader-writer" example

 3 * 2 - add per-molecule logging; log to file or to logger function

 3 * 3 - go through examples in Jiansen's project (done) and in my JoCaml tutorial
 
 5 * 5 - implement special handling for molecules with Future-wrapped values (inject in the future).
 Transform JA[Future[T]] => Future[JA[T]] and inject JA[T] when the future resolves.
 More generally, consider a good way of reconciling asynchronous programming and JC. The use case for this is
 a legacy API that forces async constructs on us (Future, stream, etc.), and we would like to avoid blocking
 any threads but instead to combine JC and asynchronous thread multiplexing implicit in the given async construct.
 
 4 * 5 - implement multiple injection construction a+b+c so that a+b-> and b+c-> reactions are equally likely to start.
 
 4 * 5 - allow several reactions to be scheduled simultaneously out of the same join definition, when this is possible. Avoid locking the entire bag - perhaps, partition it, based on join definition information gleaned using a macro.
 
 5 * 5 - implement "progress and safety" assertions so that we could prevent deadlock in more cases
 and be able to better reason about our declarative reactions.

 2 * 4 - allow molecule values to be parameterized types or even higher-kinded types?

 2 * 2 - make memory profiling / benchmarking; how many molecules can we have per 1 GB of RAM?

 3 * 3 - use "blocking" from Scala's ExecutionContext, and use Scala futures (or Java Futures? or Promises?) with
 timeouts (or simply timeout on a semaphore's acquire?).
 Introduce a feature that times out on a blocking molecule.
 val f = new JS[T,R]
 f(timeoutNanos = 10000000L)(t)
  * */

import DefaultValue.defaultValue
import java.util.concurrent.{Semaphore, TimeUnit}

import scala.collection.mutable
import scala.reflect.ClassTag

// A pool of execution threads, or another way of running tasks (could use actors or whatever else).

trait JPool {
  def runClosure(task: => Unit): Unit
  def shutdownNow(): Unit
  def apply(body: JoinRun.ReactionBody) = JoinRun.Reaction(body, this)
}

object JoinRun {

  // These type aliases are intended for users.
  type JA[T] = AsynMol[T]
  type JS[T,R] = SynMol[T,R]

  // Users will call these functions to create new molecules (a.k.a. "molecule injectors").
  def ja[T: ClassTag] = new AsynMol[T](None)
  def js[T: ClassTag,R] = new SynMol[T,R](None)
  def ja[T: ClassTag](name: String) = new AsynMol[T](Some(name))
  def js[T: ClassTag,R](name: String) = new SynMol[T,R](Some(name))

  // Wait until the join definition to which `molecule` belongs becomes quiescent, then inject `callback`.
  // TODO: implement
  def wait_until_quiet[T](molecule: AsynMol[T], callback: AsynMol[Unit]): Unit = {
    molecule.joinDef match {
      case Some(owner) => owner.setQuiescenceCallback(callback)
      case None => throw new Exception(s"Molecule $molecule belongs to no join definition")
    }
  }

  implicit class JoinableUnit(x: Unit) {
    def +(n: Unit): Unit = () // just make sure they are both evaluated
  }

  object + {
    def unapply(attr:Any) = Some(attr,attr)
  }

  // Users will create reactions using these functions.
  // Examples: run { a(_) => ... }
  // run { a (_) => ...} onThreads jPool

  def run(body: ReactionBody): Reaction = Reaction(body, defaultReactionPool)

  // This is an alias for JoinRun.run, to be used in case `run` clashes with another name imported into the local scope (e.g. in  scalatest).
  object & {
    // Users will create reactions using these functions.
    def apply(body: ReactionBody): Reaction = Reaction(body, defaultReactionPool)
  }

  // Container for molecule values
  // TODO: this is ugly - refactor!
  private sealed trait AbsMolValue {
    def getValue[T]: T

    override def toString: String = getValue[Any] match { case () => ""; case v@_ => v.toString }
  }
  private case class AsyncMolValue(v: Any) extends AbsMolValue {
    override def getValue[T]: T = v.asInstanceOf[T]
  }
  private case class SyncMolValue(jsv: SyncReplyValue[_,_]) extends AbsMolValue {
    override def getValue[T]: T = jsv.v.asInstanceOf[T]
  }

  private class ExceptionInJoinRun(message: String) extends Exception(message)
  private class ExceptionNoJoinDef(message: String) extends ExceptionInJoinRun(message)
  private class ExceptionNoWrapper(message: String) extends ExceptionInJoinRun(message)
  private class ExceptionWrongInputs(message: String) extends ExceptionInJoinRun(message)
  private class ExceptionEmptyReply(message: String) extends ExceptionInJoinRun(message)

  // Abstract molecule injector. This type is used in collections of molecules that do not require knowing molecule types.
  abstract class AbsMol(name: Option[String]) {
    var joinDef: Option[JoinDefinition] = None

    def setLogLevel(logLevel: Int): Unit = { joinDef.foreach(o => o.logLevel = logLevel) }

    def getName: String = name.getOrElse(super.toString)
  }

  // Asynchronous molecule. This is an immutable class.
  private[JoinRun] class AsynMol[T: ClassTag](name: Option[String] = None) extends AbsMol(name) with Function1[T, Unit] {
    def apply(v: T): Unit = {
      // Inject an asynchronous molecule.
      joinDef match {
        case Some(o) => o.injectAsync[T](this, AsyncMolValue(v))
        case None => throw new ExceptionNoJoinDef(s"Molecule ${this} does not belong to any join definition")
      }
    }

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
          v.getValue[T]
        }

      // This is used when running the chosen reaction.
      case UnapplyRun(moleculeValues) => moleculeValues.get(this).map(_.getValue[T])
    }
  }

  // Reply-value wrapper for synchronous molecules. This is a mutable class.
  private[JoinRun] case class SyncReplyValue[T, R](
    v: T,
    var result: Option[R] = None,
    var semaphore: Semaphore = { val s = new Semaphore(0, true); s.drainPermits(); s },
    var errorMessage: Option[String] = None,
    var repliedTwice: Boolean = false
  ) {
    def releaseSemaphore() = if (semaphore != null) semaphore.release()

    def acquireSemaphore(timeoutNanos: Option[Long] = None): Unit =
      if (semaphore != null)
        timeoutNanos match {
          case Some(nanos) => semaphore.tryAcquire(nanos, TimeUnit.NANOSECONDS)
          case None => semaphore.acquire()
        }

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

  // Synchronous molecule injector. This is an immutable value.
  private[JoinRun] class SynMol[T: ClassTag,R](name: Option[String] = None) extends AbsMol(name) with Function1[T,R] {

    def apply(v: T): R = {
      // Inject a synchronous molecule.
      joinDef.map(_.injectSyncAndReply[T,R](this, SyncReplyValue[T,R](v)))
        .getOrElse(throw new ExceptionNoJoinDef(s"Molecule $this does not belong to any join definition"))
    }

    override def toString: String = getName + "/S"

    def unapply(arg: UnapplyArg): Option[(T, SyncReplyValue[T,R])] = arg match {
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
        Some((defaultValue[T], null).asInstanceOf[(T, SyncReplyValue[T,R])]) // hack. This value will not be used.

      // This is used just before running the actual reactions, to determine which ones pass all the pattern-matching tests.
      // We also gather the information about the molecule values actually used by the reaction, in case the reaction can start.
      case UnapplyRunCheck(moleculeValues, usedInputs) =>
        for {
          v <- moleculeValues.getOne(this)
        } yield {
          usedInputs += (this -> v)
          (v.getValue[T], null).asInstanceOf[(T, SyncReplyValue[T,R])]
        }

      // This is used when running the chosen reaction.
      case UnapplyRun(moleculeValues) => moleculeValues.get(this).map {
        case SyncMolValue(jsv) => (jsv.v, jsv).asInstanceOf[(T, SyncReplyValue[T, R])]
        case m@_ => throw new ExceptionNoWrapper(s"Internal error: molecule $this with no synchronous value " +
          s"wrapper around value $m")
      }
    }
  }

  implicit val defaultJoinPool = new JJoinPool
  implicit val defaultReactionPool = new JReactionPool(4)

  private[jc] sealed trait UnapplyArg // The disjoint union type for arguments passed to the unapply methods.
  private case class UnapplyCheck(inputMolecules: mutable.Set[AbsMol]) extends UnapplyArg
  private case class UnapplyRunCheck(moleculeValues: MoleculeBag, usedInputs: MutableLinearMoleculeBag) extends UnapplyArg
  private case class UnapplyRun(moleculeValues: LinearMoleculeBag) extends UnapplyArg

  private[jc] type ReactionBody = PartialFunction[UnapplyArg, Unit]

  // immutable
  private[jc] case class Reaction(body: ReactionBody, threadPool: JPool) {
    lazy val inputMoleculesUsed: Set[AbsMol] = {
      val moleculesInThisReaction = UnapplyCheck(mutable.Set.empty)
      body.isDefinedAt(moleculesInThisReaction)
      moleculesInThisReaction.inputMolecules.toSet
    }

    def onThreads(newThreadPool: JPool): Reaction = Reaction(body, newThreadPool)

    override def toString = s"${inputMoleculesUsed.toSeq.map(_.toString).sorted.mkString(" + ")} => ..."
  }

  // Users will call join(...) in order to introduce a new Join Definition (JD).
  // All input and output molecules for this JD should have been already defined, and inputs should not yet have been used in any other JD.
  def join(rs: Reaction*)
          (implicit jReactionPool: JReactionPool,
           jJoinPool: JJoinPool): Unit = {

    val knownMolecules : Map[Reaction, Set[AbsMol]] = rs.map { r => (r, r.inputMoleculesUsed) }.toMap

    // create a join definition object holding the given reactions and inputs
    val join = new JoinDefinition(knownMolecules)(jReactionPool, jJoinPool)

    // set the owner on all input molecules in this join definition
    knownMolecules.values.toSet.flatten.foreach { m =>
      m.joinDef match {
        case Some(owner) => throw new Exception(s"Molecule $m cannot be used as input since it was already used in $owner")
        case None => m.joinDef = Some(join)
      }
    }

  }

  private implicit class ShufflableSeq[T](a: Seq[T]) {
    def shuffle: Seq[T] = scala.util.Random.shuffle(a)
  }

  // for JA[T] molecules, the value inside AbsMolValue is of type T; for JS[T,R] molecules, the value is of type SyncReplyValue[T,R]
  private type MoleculeBag = MutableBag[AbsMol, AbsMolValue]
  private type MutableLinearMoleculeBag = mutable.Map[AbsMol, AbsMolValue]
  private type LinearMoleculeBag = Map[AbsMol, AbsMolValue]

  // The user will never see any instances of this class.
  private[JoinRun] class JoinDefinition(val inputMolecules: Map[Reaction, Set[AbsMol]])
                                       (var jReactionPool: JReactionPool, var jJoinPool: JJoinPool) {

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
    private val moleculesPresent: MoleculeBag = new MutableBag[AbsMol, AbsMolValue]

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
    def injectAsync[T](m: AbsMol, jmv: AbsMolValue): Unit = if (!Thread.currentThread().isInterrupted) jJoinPool.runClosure {
      val (reaction, usedInputs: LinearMoleculeBag) = synchronized {
        moleculesPresent.addToBag(m, jmv)
        if (logLevel > 0) println(s"Debug: $this injecting $m($jmv) on thread pool $jJoinPool, now have molecules ${moleculeBagToString(moleculesPresent)}")
        val usedInputs: MutableLinearMoleculeBag = mutable.Map.empty
        val reaction = possibleReactions.get(m)
          .flatMap(_.shuffle.find(r => {
            usedInputs.clear()
            r.body.isDefinedAt(UnapplyRunCheck(moleculesPresent, usedInputs))
          }))
        reaction match {
          case Some(r) =>
            // If we are here, we have found a reaction that can be started.
            // We need to remove the input molecules from the bag, as per JC execution semantics.
            moleculesPresent.removeFromBag(usedInputs)
          case None => ()
        }
        (reaction, usedInputs.toMap)
      } // End of synchronized block.
      // We are just starting a reaction, so we don't need to hold the thread any more.
      reaction match {
        case Some(r) =>
          if (logLevel > 1) println(s"Debug: In $this: starting reaction {$r} on thread pool ${r.threadPool} while on thread pool $jJoinPool with inputs ${moleculeBagToString(usedInputs)}")
          if (logLevel > 2) println(
            if (moleculesPresent.size == 0)
              s"Debug: In $this: no molecules remaining"
            else
              s"Debug: In $this: remaining molecules ${moleculeBagToString(moleculesPresent)}"
          )
          // A basic check that we are using our mutable structures safely. We should never see this message.
          if (! r.inputMoleculesUsed.equals(usedInputs.keys.toSet)) {
            val message = s"Internal error: In $this: attempt to start reaction {$r} with incorrect inputs ${moleculeBagToString(usedInputs)}"
            println(message)
            throw new ExceptionWrongInputs(message)
          }
          // Build a closure out of the reaction, and run that closure on the reaction's thread pool.
          if (!Thread.currentThread().isInterrupted) r.threadPool.runClosure {
            if (logLevel > 1) println(s"Debug: In $this: reaction {$r} started on thread pool $jJoinPool with thread id ${Thread.currentThread().getId}")
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
                usedInputs.foreach { case (mol, v) => injectAsync(mol, v) }
                println(s"In $this: Reaction {$r} produced an exception. Input molecules ${moleculeBagToString(usedInputs)} were injected again. Exception trace will be printed now.")
                e.printStackTrace() // This will be printed asynchronously, out of order with the previous message.
            }
            // For any synchronous input molecules that have no reply, put an error message into them and reply with empty value to unblock the threads.

            def nonemptyOpt[S](s: Seq[S]): Option[Seq[S]] = if (s.isEmpty) None else Some(s)

            // Compute error messages here in case we will need them later.
            val syncMoleculesWithNoReply = nonemptyOpt(usedInputs
              .filter { case (_, SyncMolValue(jsv)) => jsv.result.isEmpty; case _ => false }
              .keys.toSeq).map(_.map(_.toString).sorted.mkString(", "))

            val messageNoReply = syncMoleculesWithNoReply map { s => s"Error: In $this: Reaction {$r} finished without replying to $s" }

            val syncMoleculesWithMultipleReply = nonemptyOpt(usedInputs
              .filter { case (_, SyncMolValue(jsv)) => jsv.repliedTwice; case _ => false }
              .keys.toSeq).map(_.map(_.toString).sorted.mkString(", "))

            val messageMultipleReply = syncMoleculesWithMultipleReply map { s => s"Error: In $this: Reaction {$r} replied to $s more than once" }

            // We will report all errors to each synchronous molecule.
            val errorMessage = Seq(messageNoReply, messageMultipleReply).flatten.mkString("; ")
            val haveError = syncMoleculesWithNoReply.nonEmpty || syncMoleculesWithMultipleReply.nonEmpty

            // Insert error messages into synchronous reply wrappers and release all semaphores.
            usedInputs.foreach {
              case (_, SyncMolValue(jsv)) =>
                if (haveError) {
                  jsv.errorMessage = Some(errorMessage)
                }
                jsv.releaseSemaphore()

              case _ => ()
            }

            if (haveError) {
              println(errorMessage)
              throw new Exception(errorMessage)
            }
          }

        case None =>
          if (logLevel > 2) println(s"Debug: In $this: no reactions started")
          ()

      }

    }


    // Adding a synchronous molecule may trigger at most one reaction and must return a value of type R.
    // We must make this a blocking call, so we acquire a semaphore (with timeout).
    def injectSyncAndReply[T,R](m: SynMol[T,R], valueWithResult: SyncReplyValue[T,R]): R = {
      injectAsync(m, SyncMolValue(valueWithResult))
//      try  // not sure we need this.
        valueWithResult.acquireSemaphore()
//      catch {
//        case e: InterruptedException => e.printStackTrace()
//      }
      valueWithResult.deleteSemaphore() // make sure it's gone

      // check if we had any errors, and that we have a result value
      valueWithResult.errorMessage match {
        case Some(message) => throw new Exception(message)
        case None => valueWithResult.result match {
          case Some(result) => result
          case None => throw new ExceptionEmptyReply(s"Internal error: In $this: $m received an empty reply without " +
            s"an error message")
        }

      }
    }
  }

}
