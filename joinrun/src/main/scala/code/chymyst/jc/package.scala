package code.chymyst

import scala.language.experimental.macros
import scala.collection.JavaConverters.asScalaIteratorConverter
import scala.util.{Failure, Success, Try}

/** This is a pure interface to other functions to make them visible to users.
  * This object does not contain any new code.
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
    * @param sitePool     Thread pool for use when making decisions to schedule reactions.
    * @return List of warning messages.
    */
  def site(reactionPool: Pool, sitePool: Pool)(reactions: Reaction*): WarningsAndErrors = {

    // Create a reaction site object holding the given local chemistry.
    // The constructor of ReactionSite will perform static analysis of all given reactions.
    val reactionSite = new ReactionSite(reactions, reactionPool, sitePool)

    reactionSite.checkWarningsAndErrors()
  }

  def site(reactions: Reaction*): WarningsAndErrors = site(defaultReactionPool, defaultSitePool)(reactions: _*)

  def site(reactionPool: Pool)(reactions: Reaction*): WarningsAndErrors = site(reactionPool, reactionPool)(reactions: _*)

  /**
    * Users will define reactions using this function.
    * Examples: {{{ go { a(_) => ... } }}}
    * {{{ go { a (_) => ...}.withRetry onThreads threadPool }}}
    *
    * The macro also obtains statically checkable information about input and output molecules in the reaction.
    *
    * @param reactionBody The body of the reaction. This must be a partial function with pattern-matching on molecules.
    * @return A [[Reaction]] value, containing the reaction body as well as static information about input and output molecules.
    */
  def go(reactionBody: Core.ReactionBody): Reaction = macro BlackboxMacros.buildReactionImpl

  /**
    * Convenience syntax: users can write a(x)+b(y) to emit several molecules at once.
    * (However, the molecules are emitted one by one in the present implementation.)
    *
    * @param x the first emitted molecule
    * @return a class with a + operator
    */
  implicit final class EmitMultiple(x: Unit) {
    def +(n: Unit): Unit = ()
  }

  /** Declare a new non-blocking molecule emitter.
    * The name of the molecule will be automatically assigned (via macro) to the name of the enclosing variable.
    *
    * @tparam T Type of the value carried by the molecule.
    * @return A new instance of class [[code.chymyst.jc.M]] if `T` is not `Unit`, or of class [[code.chymyst.jc.E]] if `T` is `Unit`.
    */
  def m[T]: M[T] = macro WhiteboxMacros.mImpl[T]

  /** Declare a new blocking molecule emitter.
    * The name of the molecule will be automatically assigned (via macro) to the name of the enclosing variable.
    *
    * @tparam T Type of the value carried by the molecule.
    * @tparam R Type of the reply value.
    * @return A new instance of class [[code.chymyst.jc.B]]`[T,R]` if both `T` and `R` are not `Unit`.
    *         Otherwise will return a new instance of one of the subclasses: [[code.chymyst.jc.EB]]`[R]`, [[code.chymyst.jc.BE]]`[T]`, or [[code.chymyst.jc.EE]].
    *                  */
  def b[T, R]: B[T, R] = macro WhiteboxMacros.bImpl[T, R]

  val defaultSitePool = new FixedPool(2)
  val defaultReactionPool = new FixedPool(4)

  def globalErrorLog: Iterable[String] = Core.errorLog.iterator().asScala.toIterable

  def withPool[T](pool: => Pool)(doWork: Pool => T): Try[T] = cleanup(pool)(_.shutdownNow())(doWork)

  def cleanup[T,R](resource: => T)(cleanup: T => Unit)(doWork: T => R): Try[R] = {
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

  implicit val typeIsUnit = TypeIsUnitValue

}
