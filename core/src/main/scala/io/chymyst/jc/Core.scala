package io.chymyst.jc

import java.security.MessageDigest
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

import javax.xml.bind.DatatypeConverter
import io.chymyst.util.LabeledTypes.Subtype

import scala.annotation.tailrec

/** Syntax helper for zero-argument molecule emitters.
  * This trait has a single method, `getUnit`, which returns a value of type `T`, but the only instance will exist if `T` is `Unit` and will return `()`.
  *
  * @tparam A Type of the molecule value. If this is `Unit`, we will have an implicit value of type `TypeIsUnit[A]`, which will define `getUnit` to return `()`.
  */
sealed trait TypeMustBeUnit[A] {
  @inline val getUnit: A
}

/** Syntax helper for molecules with unit values.
  * An implicit value of [[TypeMustBeUnit]]`[A]` is available only if `A == Unit`.
  */
object UnitTypeMustBeUnit extends TypeMustBeUnit[Unit] {
  override final val getUnit: Unit = ()
}

object Core {

  private[jc] val ReactionSiteId = Subtype[Long]
  private[jc] type ReactionSiteId = ReactionSiteId.T

  private[jc] val ReactionSiteString = Subtype[String]
  private[jc] type ReactionSiteString = ReactionSiteString.T

  private[jc] val ReactionString = Subtype[String]
  private[jc] type ReactionString = ReactionString.T

  private[jc] val MolSiteIndex = Subtype[Int]
  private[jc] type MolSiteIndex = MolSiteIndex.T

  private[jc] val ClusterSessionId = Subtype[Long]
  private[jc] type ClusterSessionId = ClusterSessionId.T

  private[jc] val ValTypeSymbol = Subtype[Symbol]
  private[jc] type ValTypeSymbol = ValTypeSymbol.T

  private[jc] val MolNameSymbol = Subtype[Symbol]
  private[jc] type MolNameSymbol = MolNameSymbol.T

  // Used for reporting molecule names.
  private[jc] val MolString = Subtype[String]
  private[jc] type MolString = MolString.T

  implicit val molStringOrdering: Ordering[MolString] = { (x: MolString, y: MolString) ⇒ x.compare(y) } // same as for String

  private val globalReactionSiteIdCounter: AtomicLong = new AtomicLong(0L)

  private[jc] def nextReactionSiteId = ReactionSiteId(globalReactionSiteIdCounter.incrementAndGet())

  // For each sha1 hash (computed with molecule names), store the number of known RSs with this hash.
  private val globalReactionSiteCount: scala.collection.concurrent.Map[String, AtomicInteger] = scala.collection.concurrent.TrieMap()

  private val emittersBoundToStaticReactionSites: scala.collection.concurrent.Map[String, Array[MolEmitter]] = scala.collection.concurrent.TrieMap()

  private[jc] def registerReactionSite(reactionSite: ReactionSite): AtomicInteger = {
    val count = globalReactionSiteCount.getOrElseUpdate(reactionSite.sha1CodeWithNames, new AtomicInteger(0))
    // We will not store the emitters in `emittersBoundToStaticReactionSites` if there exist more than one reaction with the same sha1 hash.
    if (count.incrementAndGet() === 1)
      emittersBoundToStaticReactionSites.update(
        reactionSite.sha1CodeWithNames,
        Array.tabulate(reactionSite.moleculeAtIndex.size)(reactionSite.moleculeAtIndex.apply)
      )
    count
  }

  def getMessageDigest: MessageDigest = MessageDigest.getInstance("SHA-1")

  def getSha1(c: String, md: MessageDigest): String = DatatypeConverter.printHexBinary(md.digest(c.getBytes("UTF-8")))

  //  def flatten[T](optionSeq: Option[Seq[T]]): Seq[T] = optionSeq.getOrElse(Seq())

  //  implicit final class SeqWithOption[S](val s: Seq[S]) extends AnyVal {
  //    def toOptionSeq: Option[Seq[S]] = if (s.isEmpty) None else Some(s)
  //  }

  @SuppressWarnings(Array("org.wartremover.warts.Equals"))
  implicit final class AnyOpsEquals[A](val self: A) extends AnyVal {
    @inline def ===(other: A): Boolean = self == other
  }

  @SuppressWarnings(Array("org.wartremover.warts.Equals"))
  implicit final class AnyOpsNotEquals[A](val self: A) extends AnyVal {
    // removing `[@specialized A]` to allow `extends AnyVal`
    @inline def =!=(other: A): Boolean = self != other
  }

  /** Compute the difference between sequences, enforcing type equality.
    * (The standard `diff` method will allow type mismatch, which has lead to an error before.)
    *
    * @param s Sequence whose elements need to be "subtracted".
    * @tparam T Type of sequence elements.
    */
  implicit final class SafeListDiff[T](val s: List[T]) extends AnyVal {
    @inline def difff(t: List[T]): List[T] = s diff t
  }

  /** Provide `.toScalaSymbol` method for `String` values. */
  implicit final class StringToSymbol(val s: String) extends AnyVal {
    @inline def toScalaSymbol: scala.Symbol = scala.Symbol(s)
  }

  /** Type alias for reaction body, which is used often. */
  private[jc] type ReactionBody = PartialFunction[ReactionBodyInput, Any]

  /** For each site-wide molecule, this array holds the values of the molecules actually present at the reaction site.
    * The `MutableBag` instance may be of different subclass for each molecule. */
  private[jc] type MoleculeBagArray = Array[MutableBag[AbsMolValue[_]]]

  private[jc] def moleculeBagToString(mb: Map[MolEmitter, Map[AbsMolValue[_], Int]]): String =
    mb.toSeq
      .map { case (mol, vs) => (s"$mol${pipelineSuffix(mol)}", vs) }
      .sortBy(_._1)
      .flatMap {
        case (mol, vs) => vs.map {
          case (mv, 1) => s"$mol($mv)"
          case (mv, i) => s"$mol($mv) * $i"
        }
      }.sorted.mkString(" + ")

  private[jc] def reactionInputsToString(reaction: Reaction, inputs: InputMoleculeList): String =
    inputs.indices.map { i ⇒
      val jmv = inputs(i)
      val mol = reaction.info.inputs(i).molecule
      s"$mol${pipelineSuffix(mol)}($jmv)"
    }.toSeq.sorted.mkString(" + ")

  /** The pipeline suffix is printed only in certain debug messages; the molecule's [[MolEmitter.name]] does not include that suffix.
    * The reason for this choice is that typically many molecules are automatically pipelined,
    * so output messages would be unnecessarily encumbered with the `/P` suffix.
    *
    * We would like to avoid "mol/B/P" in favor of "mol/BP", so we omit the slash if the molecule is blocking.
    */
  private def pipelineSuffix(mol: MolEmitter): String =
    if (mol.isPipelined) {
      if (mol.isBlocking) "P" else "/P"
    } else ""

  /** List of molecules used as inputs by a reaction. The molecules are ordered the same as in the reaction input list. */
  private[jc] type InputMoleculeList = Array[AbsMolValue[_]]

  /*
    implicit final class EitherMonad[L, R](val e: Either[L, R]) extends AnyVal {
      def map[S](f: R => S): Either[L, S] = e match {
        case Right(r) => Right(f(r))
        case Left(l) => Left(l)
      }

      def flatMap[S](f: R => Either[L, S]): Either[L, S] = e match {
        case Right(r) => f(r)
        case Left(l) => Left(l)
      }
    }
  */
  implicit final class ArrayWithExtraFoldOps[T](val s: Array[T]) extends AnyVal {
    def flatFoldLeft[R](z: R)(op: (R, T) => Option[R]): Option[R] = {
      var result = z
      var success = true
      var i = 0
      while (success && i < s.length) {
        op(result, s(i)) match {
          case Some(newZ) =>
            result = newZ
            i += 1
          case None =>
            success = false
        }
      }
      if (success) Some(result) else None
    }

    /** A `find` that will return the first value for which `f` returns `Some(...)`.
      *
      * This is an optimization over `find`: it does not compute the found value `f(r)` twice.
      *
      * @param f Mapping function.
      * @tparam R Type of the return value `r` under `Option`.
      * @return `Some(r)` if `f` returned a non-empty option value; `None` otherwise.
      */
    def findAfterMap[R](f: T => Option[R]): Option[R] = {
      var result: R = null.asInstanceOf[R]
      var found = false
      var i = 0
      while (!found && i < s.length) {
        f(s(i)) match {
          case Some(r) =>
            result = r
            found = true
          case None =>
            i += 1
        }
      }
      if (found) Some(result) else None
    }

  }

  implicit final class SeqWithExtraFoldOps[T](val s: Seq[T]) extends AnyVal {

    /** A `find` that will return the first value for which `f` returns `Some(...)`.
      *
      * This is an optimization over `find`: it does not compute the found value `f(r)` twice.
      *
      * @param f Mapping function.
      * @tparam R Type of the return value `r` under `Option`.
      * @return `Some(r)` if `f` returned a non-empty option value; `None` otherwise.
      */
    def findAfterMap[R](f: T => Option[R]): Option[R] = {
      var result: R = null.asInstanceOf[R]
      s.find { t =>
        f(t).exists { r => result = r; true }
      }.map(_ => result)
    }

    /** A standard `Seq#groupBy` produces a `Map` and loses the ordering present in the original sequence.
      * Instead, [[orderedMapGroupBy]] produces a sequence of tuples that preserves the ordering of the original sequence.
      * The result is not a true `groupBy` since it never reorders sequence elements.
      *
      * This is suitable for stream processing or for cases when the original sequence is sorted,
      * and we need to split the sorted sequence into sorted subsequences.
      *
      * For example, `Seq(1,2,3,4).orderedMapGroupBy(x < 3)` yields `Seq((true, Seq(1,2)), (false, Seq(3,4))`
      * and `Seq(1,2,3,2,1).orderedMapGroupBy(x < 3)` yields `Seq((true, Seq(1,2)), (false, Seq(3)), (true, Seq(2,1)))`
      *
      * @param f A function that determines the grouping key.
      * @param g A function that is applied to elements as a `map`.
      * @tparam R Type of the grouping key.
      * @tparam S Type of the elements of the resulting sequence.
      * @return Sequence of tuples similar to the output of `groupBy`.
      */
    def orderedMapGroupBy[R, S](f: T ⇒ R, g: T ⇒ S): IndexedSeq[(R, IndexedSeq[S])] =
      s.headOption match {
        case None ⇒ IndexedSeq()
        case Some(t) ⇒
          val (finalR, finalSeq, finalSeqT) =
            s.drop(1).foldLeft[(R, IndexedSeq[(R, IndexedSeq[S])], IndexedSeq[S])]((f(t), IndexedSeq(), IndexedSeq(g(t)))) {
              (acc, t) ⇒
                val (prevR, prevSeq, prevSeqT) = acc
                val newR = f(t)
                val newT = g(t)
                if (newR === prevR)
                  (newR, prevSeq, prevSeqT :+ newT)
                else
                  (newR, prevSeq :+ ((prevR, prevSeqT)), IndexedSeq(newT))
            }
          finalSeq :+ ((finalR, finalSeqT))
      }

    /** "flatMap + foldLeft" will perform a `foldLeft` unless the function `op` returns `None` at some point in the sequence.
      *
      * @param z  Initial value of type `R`.
      * @param op Binary operation, returning an `Option[R]`.
      * @tparam R Type of the return value `r` under `Option`.
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

    /*
        /** "early foldLeft" will perform a `foldLeft` until the function `op` returns `None` at some point in the sequence.
          * At that point, it stops and returns the last accumulated value.
          *
          * This is an optimization: the usual `foldLeft` will continue going through the sequence, but `earlyFoldLeft` cuts short.
          *
          * @param z  Initial value of type `R`.
          * @param op Binary operation, returning an `Option[R]`.
          * @tparam R Type of the return value `r` under `Option`.
          * @return Result value `r`, having folded either to the end of the sequence, or to the point in the sequence where `op` first returned `None`, whichever comes first.
          */
        @tailrec
        def earlyFoldLeft[R](z: R)(op: (R, T) => Option[R]): R = s match {
          case Nil => z
          case h :: xs => op(z, h) match {
            case Some(newZ) => xs.earlyFoldLeft(newZ)(op)
            case None => z
          }
        }
    */
  }

  /** A primitive but very fast algorithm for converting a sequence of integers into an integer hash value.
    * To be used only for small sequences and when true hashing is unnecessary.
    */
  def intHash(s: Seq[Int]): Int = s.foldLeft(0)(_ * s.length + _)

  /** Built-in Scala types for which there exist only relatively few distinct values.
    *
    * These are types `T` for which we can represent a multiset of values of `T` as a hash map with little or no space penalty.
    */
  val simpleTypes: Set[scala.Symbol] = Set('Unit, 'Boolean, 'Symbol, 'Char, 'Short, 'Byte, 'Null, 'Nothing)

  /*
  implicit def toJavaFunction[T, R](f: Function1[T, R]): Function[T, R] =
    new Function[T, R] {
      override def apply(t: T): R = f(t)
    }

  implicit def toJavaBiFunction[T1, T2, R](f: Function2[T1, T2, R]): BiFunction[T1, T2, R] =
    new BiFunction[T1, T2, R] {
      override def apply(t1: T1, t2: T2): R = f(t1, t2)
    }

  /** Retrieve a random element from array, reshuffling the array in place each time.
    * When this function is called consecutively with `i = 0`, `i = 1`, ..., `i = arr.length - 1`,
    * the result will contain all elements of the array, reshuffled in a random order.
    *
    * This is achieved in the following way: After calling `randomElementInArray(arr, i)`,
    * the array element at index `i` will be exchanged with a randomly chosen element with index not smaller than `i`.
    * The function then returns that randomly chosen element.
    *
    * @param arr Array of elements. Will be modified in place!
    * @param i   Index of the element to retrieve.
    * @tparam T Type of values in the array.
    * @return The retrieved element.
    */
  private def randomElementInArray[T](arr: Array[T], i: Int): T = {
    val s = arr.length
    val index = Math.min(s - 1, Math.max(0, i))
    val randomIndex = s - 1 - scala.util.Random.nextInt(s - index)
    // between 0 and s - index
    val tempElement = arr(randomIndex)
    arr(randomIndex) = arr(index)
    arr(index) = tempElement
    tempElement
  }

  def arrayShuffleInPlace[T](arr: Array[T]): Unit = {
    val s = arr.length
    //    java.util.Collections.shuffle(java.util.Arrays.asList(arr: _*))
    // Do nothing when the array has length 1 or less.
    if (s >= 2) {
      var index = 0
      while (index < s - 1) {
        val randomIndex = s - 1 - scala.util.Random.nextInt(s - 1 - index)
        // randomIndex is between index + 1 and s - 1 inclusive.
        val tempElement = arr(randomIndex)
        arr(randomIndex) = arr(index)
        arr(index) = tempElement
        index += 1
      }
    }
  }

  /** Add a random shuffle method to sequences.
    *
    * @param a Sequence to be shuffled.
    * @tparam T Type of sequence elements.
    */
  implicit final class ShufflableSeq[T](val a: Seq[T]) extends AnyVal {
    /** Shuffle sequence elements randomly.
      *
      * @return A new sequence with randomly permuted elements.
      */
    def shuffle: Seq[T] = scala.util.Random.shuffle(a)
  }

*/
  def streamDiff[T](s: Iterator[T], skipBag: MutableMultiset[T]): Iterator[T] = {
    s.filter { t ⇒
      if (skipBag contains t) {
        skipBag.remove(t)
        false
      }
      else true
    }
  }

  private[jc] def unboundOutputMoleculesString(nonStaticReactions: Array[Reaction]): String =
    nonStaticReactions
      .flatMap(_.info.outputs.filter(!_.molecule.isBound).map(_.molecule.toString))
      .toSet
      .toList
      .sorted.mkString(", ")

  /** Obtain the Chymyst reaction info string from the current thread.
    *
    * @return `"<none>"` if the current thread is not currently running a reaction.
    *         Otherwise, returns the string that describes the reaction now being run by this thread.
    */
  def getReactionInfo: ReactionString = Thread.currentThread() match {
    case t: ChymystThread ⇒
      t.reactionInfo
    case _ ⇒
      NO_REACTION_INFO_STRING
  }

  private[jc] def setReactionInfoOnThread(info: ReactionInfo): Unit = Thread.currentThread() match {
    case t: ChymystThread ⇒
      t.reactionInfoString = info.toString
    case _ ⇒
  }

  private[jc] def clearReactionInfoOfThread(): Unit = Thread.currentThread() match {
    case t: ChymystThread ⇒
      t.reactionInfoString = NO_REACTION_INFO_STRING
    case _ ⇒
  }

  val NO_REACTION_INFO_STRING = ReactionString("<none>")

  def getClusterSessionIdOfThread: Option[ClusterSessionId] = Thread.currentThread() match {
    case t: ChymystThread ⇒
      t.clusterSessionId
    case _ ⇒
      None
  }
  
  private[jc] def setClusterSessionOnThread(clusterSessionId: ClusterSessionId): Unit = Thread.currentThread() match {
    case t: ChymystThread ⇒
      t.clusterSessionIdValue = Some(clusterSessionId)
    case _ ⇒
  }

  private[jc] def clearClusterSessionOfThread(): Unit = Thread.currentThread() match {
    case t: ChymystThread ⇒
      t.clusterSessionIdValue = None
    case _ ⇒
  }


}
