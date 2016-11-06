package code.winitzki.jc

/*
This is a micro-framework for purely functional concurrency, called “join calculus” (JC).

JC is basically “Actors” but made type-safe, purely functional, and significantly more high-level.

The code is inspired by previous implementations by He Jiansen (https://github.com/Jiansen/ScalaJoin, 2011)
and Philipp Haller (http://lampwww.epfl.ch/~phaller/joins/index.html, 2008).

TODO
 * - make thread pools an Option, so that default thread pool can be used for all reactions except some
 * - go through possible values when matching (can do?) Important: can get stuck when molecules are in different order. Or need to shuffle.
 * - define a special "switch off" molecule - per-join, with a callback parameter
 * - use dsinfo to automate molecule naming
 * - benchmark multicore with some thread.sleep
 * - benchmark merge-sort
 * - benchmark dining philosophers
 * - implement disjoin
 * - implement fault tolerance (catch exception in task)
 * - implement event-driven actors for threadpool
  * */

import java.util.concurrent.Semaphore

import scala.collection.mutable

// A pool of execution threads, or another way of running tasks (could use actors or whatever else).

trait JPool {
  def runProcess(task: => Unit): Unit
  def shutdownNow(): Unit
  def apply(body: JoinRun.JReactionBody) = JoinRun.JReaction(body, this)
}

object JoinRun {

  // Wait until the join definition to which `molecule` belongs becomes quiescent, then inject `callback`.
  def wait_until_quiet[T](molecule: JAsy[T], callback: JAsy[Unit]): Unit = {
    molecule.owner match {
      case Some(owner) => owner.setQuiescenceCallback(callback)
      case None => throw new Exception(s"Molecule $molecule belongs to no join definition")
    }
  }

  implicit class JoinableUnit(x: Unit) {
    def &(n: Unit): Unit = () // just make sure they are both evaluated
    def +(n: Unit): Unit = ()
  }

  object + {
    def unapply(attr:Any) = Some(attr,attr)
  }

  object & {
    def unapply(attr:Any) = Some(attr,attr)

    // Users will create reactions using these functions.
    def apply(body: JReactionBody): JReaction = JReaction(body, defaultProcessPool)
  }

  // Users will create reactions using these functions.
  // Examples: run { a(_) => ... }
  // run { a (_) => ...} onThreads jPool

  def run(body: JReactionBody): JReaction = JReaction(body, defaultProcessPool)

  // Container for molecule values
  private[jc] sealed trait JMolValue {
    def getValue[T]: T

    override def toString: String = getValue[Any] match { case () => ""; case v@_ => v.toString }
  }
  private[jc] case class JAMV(v: Any) extends JMolValue {
    override def getValue[T]: T = v.asInstanceOf[T]
  }
  private[jc] case class JSMV(jsv: JReplyVal[_,_]) extends JMolValue {
    override def getValue[T]: T = jsv.v.asInstanceOf[T]
  }

  sealed trait MoleculeType
  case object JAsyncMoleculeType extends MoleculeType
  case object JSyncMoleculeType extends MoleculeType

  // Abstract molecule. This type is used in collections of molecules that only require to know the owner.
  private[jc] abstract class JAbs(name: Option[String]) {
    var owner: Option[JoinDefinition] = None
    def setLogLevel(logLevel: Int): Unit = { owner.foreach(o => o.logLevel = logLevel) }
    def moleculeType: MoleculeType
    override def toString: String = {
      val moleculeTypeSuffix = moleculeType match {
        case JAsyncMoleculeType => ""
        case JSyncMoleculeType => "/S"
      }

      s"${name.getOrElse(super.toString)}$moleculeTypeSuffix"
    }
  }

  def ja[T] = new JAsy[T]
  def js[T,R] = new JSyn[T,R]
  def ja[T](name: String) = new JAsy[T](Some(name))
  def js[T,R](name: String) = new JSyn[T,R](Some(name))

  // Asynchronous molecule.
  class JAsy[T](name: Option[String] = None) extends JAbs(name) {
    def apply(v: T): Unit = {
      // Inject an asynchronous molecule.
      owner match {
        case Some(o) => o.injectAsync[T](this, JAMV(v))
        case None => throw new Exception(s"Molecule ${this} does not belong to any join definition")
      }
    }

    override def moleculeType = JAsyncMoleculeType

    def unapply(arg: JUnapplyArg): Option[T] = arg match {
      // When we are gathering information about the input molecules, `unapply` will always return Some(...),
      // so that any pattern-matching on arguments will continue with null (since, at this point, we have no values).
      // Any pattern-matching will work unless null fails.
      case JUnapplyCheck(inputMoleculesProbe) =>
        if (inputMoleculesProbe contains this) {
          throw new Exception(s"Nonlinear pattern: ${this} used twice")
        }
        else
          inputMoleculesProbe.add(this)
        Some(null.asInstanceOf[T]) // hack. This value will not be used.

      // This is used just before running the actual reactions, to determine which ones pass all the pattern-matching tests.
      // We also gather the information about the molecule values actually used by the reaction, in case the reaction can start.
      case JUnapplyRunCheck(moleculeValues, usedInputs) =>
        for {
          v <- moleculeValues.getOne(this)
        } yield {
          usedInputs += (this -> v)
          v.getValue[T]
        }

      // This is used when running the chosen reaction.
      case JUnapplyRun(moleculeValues) => moleculeValues.get(this).map(_.getValue[T])
    }
  }

  // Reply-value wrapper for synchronous molecules.
  private[jc] case class JReplyVal[T, R](
    v: T,
    var result: Option[R] = None,
    var semaphore: Semaphore = { val s = new Semaphore(0, true); s.drainPermits(); s },
    var errorMessage: Option[String] = None,
    var repliedTwice: Boolean = false
  ) {
    def releaseSemaphore() = if (semaphore != null) semaphore.release()

    def acquireSemaphore(): Unit = if (semaphore != null) semaphore.acquire()

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

  // Synchronous molecule.
  private[jc] class JSyn[T,R](name: Option[String] = None) extends JAbs(name) {

    def apply(v: T): R = {
      // Inject a synchronous molecule.
      owner.map(_.injectSyncAndReply[T,R](this, JReplyVal[T,R](v)))
        .getOrElse(throw new Exception(s"Molecule $this does not belong to any join definition"))
    }

    override def moleculeType = JSyncMoleculeType

    def unapply(arg: JUnapplyArg): Option[(T, JReplyVal[T,R])] = arg match {
      // When we are gathering information about the input molecules, `unapply` will always return Some(...),
      // so that any pattern-matching on arguments will continue with null (since, at this point, we have no values).
      // Any pattern-matching will work unless null fails.
      case JUnapplyCheck(inputMoleculesProbe) =>
        if (inputMoleculesProbe contains this) {
          throw new Exception(s"Nonlinear pattern: ${this} used twice")
        }
        else
          inputMoleculesProbe.add(this)
        Some((null, null).asInstanceOf[(T, JReplyVal[T,R])]) // hack. This value will not be used.

      // This is used just before running the actual reactions, to determine which ones pass all the pattern-matching tests.
      // We also gather the information about the molecule values actually used by the reaction, in case the reaction can start.
      case JUnapplyRunCheck(moleculeValues, usedInputs) =>
        for {
          v <- moleculeValues.getOne(this)
        } yield {
          usedInputs += (this -> v)
          (v.getValue[T], null).asInstanceOf[(T, JReplyVal[T,R])]
        }

      // This is used when running the chosen reaction.
      case JUnapplyRun(moleculeValues) => moleculeValues.get(this).map {
        case JSMV(jsv) => (jsv.v, jsv).asInstanceOf[(T, JReplyVal[T, R])]
        case m@_ => throw new Exception(s"Internal error: molecule $this with no synchronous value wrapper around value $m")
      }
    }
  }

  implicit val defaultJoinPool = new JJoinPool
  implicit val defaultProcessPool = new JProcessPool(2)

  private[jc] sealed trait JUnapplyArg // The disjoint union type for arguments passed to the unapply methods.
  private[jc] case class JUnapplyCheck(inputMolecules: mutable.Set[JAbs]) extends JUnapplyArg
  private[jc] case class JUnapplyRun(moleculeValues: LinearMoleculeBag) extends JUnapplyArg
  private[jc] case class JUnapplyRunCheck(moleculeValues: MoleculeBag, usedInputs: MutableLinearMoleculeBag) extends JUnapplyArg

  private[jc] type JReactionBody = PartialFunction[JUnapplyArg, Any]

  case class JReaction(body: JReactionBody, threadPool: JPool) {
    lazy val inputMoleculesUsed: Set[JAbs] = {
      val moleculesInThisReaction = JUnapplyCheck(mutable.Set.empty)
      body.isDefinedAt(moleculesInThisReaction)
      moleculesInThisReaction.inputMolecules.toSet
    }

    def onThreads(newThreadPool: JPool): JReaction = JReaction(body, newThreadPool)

    override def toString = s"${inputMoleculesUsed.toSeq.map(_.toString).sorted.mkString(" + ")} => ..."
  }

  // Users will call join(...) in order to introduce a new Join Definition (JD).
  // All input and output molecules for this JD should have been already defined, and inputs should not yet have been used in any other JD.
  def join(rs: JReaction*)
          (implicit jProcessPool: JProcessPool,
           jJoinPool: JJoinPool): Unit = {

    val knownMolecules : Map[JReaction, Set[JAbs]] = rs.map { r => (r, r.inputMoleculesUsed) }.toMap

    // create a join definition object holding the given reactions and inputs
    val join = new JoinDefinition(knownMolecules)(jProcessPool, jJoinPool)

    // set the owner on all input molecules in this join definition
    knownMolecules.values.toSet.flatten.foreach { m =>
      m.owner match {
        case Some(owner) => throw new Exception(s"Molecule $m cannot be used as input since it was already used in $owner")
        case None => m.owner = Some(join)
      }
    }

  }

  implicit class ShufflableSeq[T](a: Seq[T]) {
    def shuffle: Seq[T] = scala.util.Random.shuffle(a)
  }

  // for JA[T] molecules, the value is of type T; for JS[T,R] molecules, the value is of type JReplyVal[T,R]
  private[jc] type MoleculeBag = MutableBag[JAbs, JMolValue]
  private[jc] type MutableLinearMoleculeBag = mutable.Map[JAbs, JMolValue]
  private[jc] type LinearMoleculeBag = Map[JAbs, JMolValue]

  // The user will never see any instances of this class.
  class JoinDefinition(val inputMolecules: Map[JReaction, Set[JAbs]])(jProcessPool: JProcessPool, jJoinPool: JJoinPool) {

    private val quiescenceCallbacks: mutable.Set[JAsy[Unit]] = mutable.Set.empty

    override def toString = s"Join{${inputMolecules.keys.mkString("; ")}}"

    var logLevel = 0

    def setQuiescenceCallback(callback: JAsy[Unit]): Unit = {
      quiescenceCallbacks.add(callback)
    }

    private lazy val possibleReactions: Map[JAbs, Seq[JReaction]] = inputMolecules.toSeq
      .flatMap { case (r, ms) => ms.toSeq.map { m => (m, r) } }
      .groupBy { case (m, r) => m }
      .map { case (m, rs) => (m, rs.map(_._2)) }

    // Initially, there are no molecules present.
    private var moleculesPresent: MoleculeBag = new MutableBag[JAbs, JMolValue]

    private def moleculeBagToString(mb: MoleculeBag): String =
      mb.getMap.flatMap {
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
    def injectAsync[T](m: JAbs, jmv: JMolValue): Unit = jJoinPool.runProcess {
      val (reaction, usedInputs: LinearMoleculeBag) = synchronized {
        moleculesPresent.addToBag(m, jmv)
        if (logLevel > 0) println(s"Debug: $this injecting $m($jmv) on thread pool $jJoinPool, now have molecules ${moleculeBagToString(moleculesPresent)}")
        val usedInputs: MutableLinearMoleculeBag = mutable.Map.empty
        val reaction = possibleReactions.get(m)
          .flatMap(_.shuffle.find(r => {
            usedInputs.clear()
            r.body.isDefinedAt(JUnapplyRunCheck(moleculesPresent, usedInputs))
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
            throw new Exception(message)
          }
          // Run the reaction process on the reaction's thread pool.
          r.threadPool.runProcess {
            if (logLevel > 1) println(s"Debug: In $this: reaction {$r} started on thread pool $jJoinPool with thread id ${Thread.currentThread().getId}")
            try {
              // Here we actually apply the reaction body to its input molecules.
              r.body.apply(JUnapplyRun(usedInputs))
            } catch {
              case e: Throwable =>
                // Running the reaction body produced an exception. Note that the exception has killed a thread.
                // We will now re-insert the input molecules. Hopefully, no side-effects or output molecules were produced so far.
                usedInputs.foreach { case (mol, v) => injectAsync(mol, v) }
                println(s"In $this: Reaction {$r} produced an exception. Input molecules ${moleculeBagToString(usedInputs)} were injected again. Exception trace will be printed.")
                e.printStackTrace() // This will be printed asynchronously, out of order with the previous message.
            }
            // For any synchronous input molecules that have no reply, put an error message into them and reply with empty value to unblock the threads.

            def nonemptyOpt[T](s: Seq[T]): Option[Seq[T]] = if (s.isEmpty) None else Some(s)

            // Compute error messages here in case we will need them later.
            val syncMoleculesWithNoReply = nonemptyOpt(usedInputs
              .filter { case (_, JSMV(jsv)) => jsv.result.isEmpty; case _ => false }
              .keys.toSeq).map(_.map(_.toString).sorted.mkString(", "))

            val messageNoReply = syncMoleculesWithNoReply map { s => s"Error: In $this: Reaction {$r} finished without replying to $s" }

            val syncMoleculesWithMultipleReply = nonemptyOpt(usedInputs
              .filter { case (_, JSMV(jsv)) => jsv.repliedTwice; case _ => false }
              .keys.toSeq).map(_.map(_.toString).sorted.mkString(", "))

            val messageMultipleReply = syncMoleculesWithMultipleReply map { s => s"Error: In $this: Reaction {$r} replied to $s more than once" }

            // We will report all errors to each synchronous molecule.
            val errorMessage = Seq(messageNoReply, messageMultipleReply).flatten.mkString("; ")
            val haveError = syncMoleculesWithNoReply.nonEmpty || syncMoleculesWithMultipleReply.nonEmpty

            // Insert error messages into synchronous reply wrappers and release semaphores.
            usedInputs.foreach {
              case p@(mol, JSMV(jsv)) =>
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
          if (logLevel > 2) println(s"Debug: joindef $this: no reactions started")
          ()

      }

    }

    // Adding a synchronous molecule may trigger at most one reaction and must return a value of type R.
    // This must be a blocking call.
    def injectSyncAndReply[T,R](m: JSyn[T,R], valueWithResult: JReplyVal[T,R]): R = {
      injectAsync(m, JSMV(valueWithResult))
      try  // not sure we need this.
        valueWithResult.acquireSemaphore()
      catch {
        case e: InterruptedException => e.printStackTrace()
      }
      valueWithResult.deleteSemaphore() // make sure it's gone

      // check if we had any errors, and that we have a result value
      valueWithResult.errorMessage match {
        case Some(message) => throw new Exception(message)
        case None => valueWithResult.result match {
          case Some(result) => result
          case None => throw new Exception(s"Internal error: $m received an empty reply without an error message")
        }

      }
    }
  }

}
