package io.chymyst

import scala.language.experimental.macros
import scala.collection.JavaConverters.asScalaIteratorConverter
import scala.util.{Failure, Success, Try}

/** This object contains code that should be visible to users of `Chymyst Core`.
  * It also serves as an interface to macros.
  * This allows users to import just one package and use all functionality of `Chymyst Core`.
  */
package object jc {

  /** A convenience method that fetches the number of CPU cores of the current machine.
    *
    * @return The number of available CPU cores.
    */
  def cpuCores: Int = Runtime.getRuntime.availableProcessors()

  /** Create a reaction site with one or more reactions.
    * All input and output molecules in reactions used in this site should have been
    * already defined, and input molecules should not be already bound to another site.
    *
    * @param reactions    One or more reactions of type [[Reaction]]
    * @param reactionPool Thread pool for running new reactions.
    * @return List of warning messages.
    */
  def site(reactionPool: Pool)(reactions: Reaction*): WarningsAndErrors = {

    // Create a reaction site object holding the given local chemistry.
    // The constructor of ReactionSite will perform static analysis of all given reactions.
    val reactionSite = new ReactionSite(reactions, reactionPool)

    reactionSite.checkWarningsAndErrors()
  }

  /** `site()` call with a default reaction pool. */
  def site(reactions: Reaction*): WarningsAndErrors = site(defaultReactionPool)(reactions: _*)

  /**
    * This is the main method for defining reactions.
    * Examples: {{{ go { a(_) => ... } }}}
    * {{{ go { a (_) => ...}.withRetry onThreads threadPool }}}
    *
    * The macro also obtains statically checkable information about input and output molecules in the reaction.
    *
    * @param reactionBody The body of the reaction. This must be a partial function with pattern-matching on molecules.
    * @return A [[Reaction]] value, containing the reaction body as well as static information about input and output molecules.
    */
  // IDEA cannot resolve symbol `BlackboxMacros`, but compilation works.
  def go(reactionBody: Core.ReactionBody): Reaction = macro BlackboxMacros.buildReactionImpl

  /**
    * Convenience syntax: users can write `a(x) + b(y)` to emit several molecules at once.
    * However, the molecules are still emitted one by one in the present implementation.
    * So, `a(x) + b(y) + c(z)` is equivalent to `a(x); b(y); c(z)`.
    *
    * @param x the first emitted molecule
    * @return An auxiliary class with a `+` operation.
    */
  // Making this `extend AnyVal` crashes JVM in tests!
  implicit final class EmitMultiple(x: Unit) {
    def +(n: Unit): Unit = ()
  }

  /** Declare a new non-blocking molecule emitter.
    * The name of the molecule will be automatically assigned (via macro) to the name of the enclosing variable.
    *
    * @tparam T Type of the value carried by the molecule.
    * @return A new instance of class [[io.chymyst.jc.M]]`[T]`.
    */
  def m[T]: M[T] = macro MoleculeMacros.mImpl[T]

  /** Declare a new blocking molecule emitter.
    * The name of the molecule will be automatically assigned (via macro) to the name of the enclosing variable.
    *
    * @tparam T Type of the value carried by the molecule.
    * @tparam R Type of the reply value.
    * @return A new instance of class [[io.chymyst.jc.B]]`[T,R]`.
    */
  def b[T, R]: B[T, R] = macro MoleculeMacros.bImpl[T, R]

  val defaultReactionPool = new FixedPool(4)

  /** Access the global error log used by all reaction sites to report runtime errors.
    *
    * @return An `Iterable` representing the complete error log.
    */
  def globalErrorLog: Iterable[String] = Core.errorLog.iterator().asScala.toIterable

  /** Clear the global error log used by all reaction sites to report runtime errors.
    *
    */
  def clearErrorLog(): Unit = Core.errorLog.clear()

  /** A helper method to run a closure that uses a thread pool, safely closing the pool after use.
    *
    * @param pool   A thread pool value, evaluated lazily - typically `new SmartPool(...)`.
    * @param doWork A closure, typically containing a `site(pool)(...)` call.
    * @tparam T Type of the value returned by the closure.
    * @return The value returned by the closure, wrapped in a `Try`.
    */
  def withPool[T](pool: => Pool)(doWork: Pool => T): Try[T] = cleanup(pool)(_.shutdownNow())(doWork)

  /** Run a closure with a resource that is allocated and safely cleaned up after use.
    * Resource will be cleaned up even if the closure throws an exception.
    *
    * @param resource A value of type `T` that needs to be created for use by `doWork`.
    * @param cleanup  A closure that will perform the necessary cleanup on the resource.
    * @param doWork   A closure that will perform useful work, using the resource.
    * @tparam T Type of the resource value.
    * @tparam R Type of the result of `doWork`.
    * @return The value returned by `doWork`, wrapped in a `Try`.
    */
  def cleanup[T, R](resource: => T)(cleanup: T => Unit)(doWork: T => R): Try[R] = {
    try {
      Success(doWork(resource))
    } catch {
      case e: Exception => Failure(e)
    }
    finally {
      try {
        if (Option(resource).isDefined) {
          cleanup(resource)
        }
      } catch {
        case e: Exception => e.printStackTrace()
      }
    }
  }

  /** We need to have a single implicit instance of [[TypeMustBeUnit]]. */
  implicit val _: TypeMustBeUnit[Unit] = TypeMustBeUnitValue

}
