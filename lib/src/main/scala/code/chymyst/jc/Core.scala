package code.chymyst.jc

import java.util.concurrent.ConcurrentLinkedQueue

import scala.collection.JavaConverters.asScalaIteratorConverter
import scala.collection.mutable

object Core {

  /** A special value for {{{ReactionInfo}}} to signal that we are not running a reaction.
    *
    */
  val emptyReactionInfo = ReactionInfo(Array(), Array(), AllMatchersAreTrivial, "")

  private lazy val sha1Digest = java.security.MessageDigest.getInstance("SHA-1")

  def getSha1String(c: String): String = sha1Digest.digest(c.getBytes("UTF-8")).map("%02X".format(_)).mkString

  def getSha1(c: Any): String = getSha1String(c.toString)

  // not sure if this will be useful:
  //  def flatten[T](optionSet: Option[Set[T]]): Set[T] = optionSet.getOrElse(Set())
  //  def flatten[T](optionSeq: Option[Seq[T]]): Seq[T] = optionSeq.getOrElse(Seq())

  implicit class SeqWithOption[S](s: Seq[S]) {
    def toOptionSeq: Option[Seq[S]] = if (s.isEmpty) None else Some(s)
  }

  /** Add a random shuffle method to sequences.
    *
    * @param a Sequence to be shuffled.
    * @tparam T Type of sequence elements.
    */
  implicit final class ShufflableSeq[T](a: Seq[T]) {
    /** Shuffle sequence elements randomly.
      *
      * @return A new sequence with randomly permuted elements.
      */
    def shuffle: Seq[T] = scala.util.Random.shuffle(a)
  }
  @SuppressWarnings(Array("org.wartremover.warts.Equals"))
  implicit final class AnyOpsEquals[@specialized A](self: A) {
    def ===(other: A): Boolean = self == other
  }

  @SuppressWarnings(Array("org.wartremover.warts.Equals"))
  implicit final class AnyOpsNotEquals[@specialized A](self: A) {
    def =!=(other: A): Boolean = self != other
  }

  implicit final class SafeSeqDiff[T](s: Seq[T]) {
    def difff(t: Seq[T]): Seq[T] = s diff t
  }

  implicit final class SafeListDiff[T](s: List[T]) {
    def difff(t: List[T]): List[T] = s diff t
  }

  implicit final class StringToSymbol(s: String) {
    def toScalaSymbol: scala.Symbol = scala.Symbol(s)
  }

  val defaultSitePool = new FixedPool(2)
  val defaultReactionPool = new FixedPool(4)


  // Wait until the reaction site to which `molecule` is bound becomes quiescent, then emit `callback`.
  // TODO: implement
//  def waitUntilQuiet[T](molecule: M[T], callback: E): Unit = molecule.site.setQuiescenceCallback(callback)

  /** Type alias for reaction body.
    *
    */
  private[jc] type ReactionBody = PartialFunction[UnapplyArg, Any]

  // for M[T] molecules, the value inside AbsMolValue[T] is of type T; for B[T,R] molecules, the value is of type
  // ReplyValue[T,R]. For now, we don't use shapeless to enforce this typing relation.
  private[jc] type MoleculeBag = MutableBag[Molecule, AbsMolValue[_]]
  private[jc] type MutableLinearMoleculeBag = mutable.Map[Molecule, AbsMolValue[_]]
  private[jc] type LinearMoleculeBag = Map[Molecule, AbsMolValue[_]]

  private[jc] def moleculeBagToString(mb: MoleculeBag): String =
    mb.getMap.toSeq
      .map{ case (m, vs) => (m.toString, vs) }
      .sortBy(_._1)
      .flatMap {
        case (m, vs) => vs.map {
          case (mv, 1) => s"$m($mv)"
          case (mv, i) => s"$m($mv) * $i"
        }
      }.sorted.mkString(", ")

  private[jc] def moleculeBagToString(mb: LinearMoleculeBag): String =
    mb.map {
      case (m, jmv) => s"$m($jmv)"
    }.toSeq.sorted.mkString(", ")

  def site(reactions: Reaction*): WarningsAndErrors = site(defaultReactionPool, defaultSitePool)(reactions: _*)
  def site(reactionPool: Pool)(reactions: Reaction*): WarningsAndErrors = site(reactionPool, reactionPool)(reactions: _*)

  /** Create a reaction site with one or more reactions.
    * All input and output molecules in reactions used in this site should have been
    * already defined, and input molecules should not be already bound to another site.
    *
    * @param reactions One or more reactions of type [[Reaction]]
    * @param reactionPool Thread pool for running new reactions.
    * @param sitePool Thread pool for use when making decisions to schedule reactions.
    * @return List of warning messages.
    */
  def site(reactionPool: Pool, sitePool: Pool)(reactions: Reaction*): WarningsAndErrors = {

    // Create a reaction site object holding the given local chemistry.
    // The constructor of ReactionSite will perform static analysis of all given reactions.
    val reactionSite = new ReactionSite(reactions, reactionPool, sitePool)

    reactionSite.checkWarningsAndErrors()
  }

  private val errorLog = new ConcurrentLinkedQueue[String]

  private[jc] def reportError(message: String): Unit = {
    errorLog.add(message)
    ()
  }

  def globalErrorLog: Iterable[String] = errorLog.iterator().asScala.toIterable

}
