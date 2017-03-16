package io.chymyst.jc

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

//import java.util.function.{Function, BiFunction}

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.{Left, Right}


/** Syntax helper for zero-argument molecule emitters.
  *
  * @tparam A Type of the molecule value. If this is `Unit`, we will have an implicit value of type `TypeIsUnit[A]`, which will provide extra functionality.
  */
sealed trait TypeMustBeUnit[A] {
  type UnapplyType

  def getUnit: A
}

/** Syntax helper for molecules with unit values.
  * A value of [[TypeMustBeUnit]]`[A]` is available only for `A == Unit`.
  */
object TypeMustBeUnitValue extends TypeMustBeUnit[Unit] {
  override type UnapplyType = Boolean

  override def getUnit: Unit = ()
}

object Core {
  private lazy val sha1Digest = java.security.MessageDigest.getInstance("SHA-1")

  private val longId: AtomicLong = new AtomicLong(0L)

  private[jc] def getNextId: Long = longId.incrementAndGet()

  def getSha1String(c: String): String = sha1Digest.digest(c.getBytes("UTF-8")).map("%02X".format(_)).mkString

  def getSha1(c: Any): String = getSha1String(c.toString)

  //  def flatten[T](optionSeq: Option[Seq[T]]): Seq[T] = optionSeq.getOrElse(Seq())

  implicit final class SeqWithOption[S](val s: Seq[S]) extends AnyVal {
    def toOptionSeq: Option[Seq[S]] = if (s.isEmpty) None else Some(s)
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

  @SuppressWarnings(Array("org.wartremover.warts.Equals"))
  implicit final class AnyOpsEquals[A](val self: A) extends AnyVal {
    def ===(other: A): Boolean = self == other
  }

  @SuppressWarnings(Array("org.wartremover.warts.Equals"))
  implicit final class AnyOpsNotEquals[A](val self: A) extends AnyVal {
    // removing `[@specialized A]` to allow `extends AnyVal`
    def =!=(other: A): Boolean = self != other
  }

  implicit final class SafeSeqDiff[T](val s: Seq[T]) extends AnyVal {
    def difff(t: Seq[T]): Seq[T] = s diff t
  }

  implicit final class SafeListDiff[T](val s: List[T]) extends AnyVal {
    def difff(t: List[T]): List[T] = s diff t
  }

  implicit final class StringToSymbol(val s: String) extends AnyVal {
    def toScalaSymbol: scala.Symbol = scala.Symbol(s)
  }

  /** Type alias for reaction body.
    *
    */
  private[jc] type ReactionBody = PartialFunction[ReactionBodyInput, Any]

  // for M[T] molecules, the value inside AbsMolValue[T] is of type T; for B[T,R] molecules, the value is of type
  // ReplyValue[T,R]. For now, we don't use shapeless to enforce this typing relation.
  //  private[jc] type MoleculeBag = MutableBag[Molecule, AbsMolValue[_]]
  private[jc] type MutableLinearMoleculeBag = mutable.Map[Molecule, AbsMolValue[_]]

  private[jc] type MoleculeBagArray = Array[MolValueBag[AbsMolValue[_]]]

  //  private[jc] def moleculeBagToString(mb: MoleculeBag): String = moleculeBagToString(mb.getMap)

  private[jc] def moleculeBagToString(mb: Map[Molecule, Map[AbsMolValue[_], Int]]): String =
    mb.toSeq
      .map { case (mol, vs) => (s"$mol${pipelineSuffix(mol)}", vs) }
      .sortBy(_._1)
      .flatMap {
        case (mol, vs) => vs.map {
          case (mv, 1) => s"$mol($mv)"
          case (mv, i) => s"$mol($mv) * $i"
        }
      }.sorted.mkString(" + ")

  private[jc] def moleculeBagToString(inputs: InputMoleculeList): String =
    inputs.map {
      case (mol, jmv) => s"$mol${pipelineSuffix(mol)}($jmv)"
    }.toSeq.sorted.mkString(" + ")

  private def pipelineSuffix(mol: Molecule): String =
    if (mol.isPipelined)
      "/P"
    else
      ""

  private[jc] val errorLog = new ConcurrentLinkedQueue[String]

  private[jc] def reportError(message: String): Unit = {
    errorLog.add(message)
    ()
  }

  // TODO: simplify InputMoleculeList to an array of AbsMolValue, eliminating the tuple - we already have the input list and it's fixed for each reaction at compile time
  /** List of molecules used as inputs by a reaction. */
  type InputMoleculeList = Array[(Molecule, AbsMolValue[_])]

  // Type used as argument for ReactionBody.
  type ReactionBodyInput = (Int, InputMoleculeList)

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

    def findAfterMap[R](f: T => Option[R]): Option[R] = {
      var result: R = null.asInstanceOf[R]
      var found = false
      var i = 0
      while(!found && i < s.length) {
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
*/

  def arrayShuffleInPlace[T](arr: Array[T]): Unit = {
    val s = arr.length
    // Do nothing when the array has length 1 or less.
    if (s >= 2) {
      (0 to s - 2).foreach { index ⇒
        val randomIndex = s - 1 - scala.util.Random.nextInt(s - 1 - index)
        // randomIndex is between index + 1 and s - 1 inclusive.
        val tempElement = arr(randomIndex)
        arr(randomIndex) = arr(index)
        arr(index) = tempElement
      }

    }
  }

}
