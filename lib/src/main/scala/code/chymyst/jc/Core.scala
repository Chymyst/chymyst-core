package code.chymyst.jc

import java.util.concurrent.ConcurrentLinkedQueue

import scala.annotation.tailrec
import scala.collection.JavaConverters.asScalaIteratorConverter
import scala.collection.mutable
import scala.util.{Left, Right}

object Core {

  /** A special value for `ReactionInfo` to signal that we are not running a reaction.
    *
    */
  val emptyReactionInfo = ReactionInfo(Array(), Array(), AllMatchersAreTrivial, "")

  private lazy val sha1Digest = java.security.MessageDigest.getInstance("SHA-1")

  def getSha1String(c: String): String = sha1Digest.digest(c.getBytes("UTF-8")).map("%02X".format(_)).mkString

  def getSha1(c: Any): String = getSha1String(c.toString)

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

  // Wait until the reaction site to which `molecule` is bound becomes quiescent, then emit `callback`.
  // TODO: implement
  //  def waitUntilQuiet[T](molecule: M[T], callback: E): Unit = molecule.site.setQuiescenceCallback(callback)


  val defaultSitePool = new FixedPool(2)
  val defaultReactionPool = new FixedPool(4)

  /** Type alias for reaction body.
    *
    */
  private[jc] type ReactionBody = PartialFunction[InputMoleculeList, Any]

  // for M[T] molecules, the value inside AbsMolValue[T] is of type T; for B[T,R] molecules, the value is of type
  // ReplyValue[T,R]. For now, we don't use shapeless to enforce this typing relation.
  private[jc] type MoleculeBag = MutableBag[Molecule, AbsMolValue[_]]
  private[jc] type MutableLinearMoleculeBag = mutable.Map[Molecule, AbsMolValue[_]]

  private[jc] def moleculeBagToString(mb: MoleculeBag): String =
    mb.getMap.toSeq
      .map { case (m, vs) => (m.toString, vs) }
      .sortBy(_._1)
      .flatMap {
        case (m, vs) => vs.map {
          case (mv, 1) => s"$m($mv)"
          case (mv, i) => s"$m($mv) * $i"
        }
      }.sorted.mkString(", ")

  private[jc] def moleculeBagToString(inputs: InputMoleculeList): String =
    inputs.map {
      case (m, jmv) => s"$m($jmv)"
    }.toSeq.sorted.mkString(", ")

  def site(reactions: Reaction*): WarningsAndErrors = site(defaultReactionPool, defaultSitePool)(reactions: _*)

  def site(reactionPool: Pool)(reactions: Reaction*): WarningsAndErrors = site(reactionPool, reactionPool)(reactions: _*)

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

  private val errorLog = new ConcurrentLinkedQueue[String]

  private[jc] def reportError(message: String): Unit = {
    errorLog.add(message)
    ()
  }

  def globalErrorLog: Iterable[String] = errorLog.iterator().asScala.toIterable

  // List of molecules used as inputs by a reaction.
  type InputMoleculeList = Array[(Molecule, AbsMolValue[_])]

  implicit class EitherMonad[L, R](e: Either[L, R]) {
    def map[S](f: R => S): Either[L, S] = e match {
      case Right(r) => Right(f(r))
      case Left(l) => Left(l)
    }

    def flatMap[S](f: R => Either[L, S]): Either[L, S] = e match {
      case Right(r) => f(r)
      case Left(l) => Left(l)
    }
  }

  implicit class SeqWithFlatFoldLeft[T](s: Seq[T]) {

    def findAfterMap[R](f: T => Option[R]): Option[R] = {
      var result: R = null.asInstanceOf[R]
      s.find { t =>
        f(t).exists { r => result = r; true }
      }.map(_ => result)
    }

    /** "flat foldLeft" will perform a `foldLeft` unless the function `op` returns `None` at some point in the sequence.
      *
      * @param z Initial value of type `R`.
      * @param op Binary operation, returns an `Option[R]`.
      * @tparam R Type of the return value `r` under option.
      * @return Result value `Some(r)`, having folded to the end of the sequence. Will return `None` if `op` returned `None` at any point.
      */
    def flatFoldLeft[R](z: R)(op: (R, T) => Option[R]): Option[R] = {

      @tailrec
      def flatFoldLeftImpl(z: R, xs: Seq[T]): Option[R] =
        xs.headOption match {
          case Some(h) =>
            op(z, h) match {
              case Some(newZ) => flatFoldLeftImpl(newZ, xs.drop(1))
              case None => None
            }
          case None => Some(z)

        }

      flatFoldLeftImpl(z, s)
    }
  }

}

