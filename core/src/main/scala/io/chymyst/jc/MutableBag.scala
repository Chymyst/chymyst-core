package io.chymyst.jc


import java.util.concurrent.ConcurrentLinkedQueue

import scala.collection.JavaConverters.{asScalaIteratorConverter, asScalaSetConverter}
import com.google.common.collect.ConcurrentHashMultiset

import scala.collection.mutable

/** Abstract container with multiset functionality. Concrete implementations may optimize for specific access patterns.
  *
  * @tparam T Type of the value carried by molecule.
  */
sealed trait MutableBag[T] {
  def isEmpty: Boolean

  def size: Int

  def add(v: T): Unit

  def remove(v: T): Boolean

  def find(predicate: T => Boolean): Option[T]

  def takeOne: Option[T]

  def takeAny(count: Int): Seq[T]

  def getCountMap: Map[T, Int]

  /** List all values, perhaps with repetitions.
    * It is not guaranteed that the values will be repeated the correct number of times.
    *
    * @return A stream of values.
    */
  def allValues: Stream[T]

  /** List all values, with repetitions, excluding values from a given sequence (which can also contain repeated values).
    * It is guaranteed that the values will be repeated the correct number of times.
    *
    * @param skipping A sequence of values that should be skipped while running the iterator.
    * @return A stream of values.
    */
  def allValuesSkipping(skipping: MutableMultiset[T]): Stream[T]
}

/** Implementation using guava's `com.google.common.collect.ConcurrentHashMultiset`.
  *
  * This is suitable for types that have a small number of possible values (i.e. [[Core.simpleTypes]]),
  * or for molecules constrained by cross-molecule dependencies where selection by value is important.
  */
final class MutableMapBag[T] extends MutableBag[T] {
  private val bag = ConcurrentHashMultiset.create[T]()

  override def isEmpty: Boolean = bag.isEmpty

  override def size: Int = bag.size

  override def add(v: T): Unit = {
    bag.add(v, 1)
    ()
  }

  override def remove(v: T): Boolean = bag.removeExactly(v, 1)

  override def find(predicate: (T) => Boolean): Option[T] =
    bag.createEntrySet().asScala
      .map(_.getElement)
      .find(predicate)

  override def takeAny(count: Int): Seq[T] = bag.iterator().asScala
    .take(count)
    .toSeq

  override def takeOne: Option[T] = {
    val iterator = bag.iterator
    if (iterator.hasNext)
      Some(iterator.next)
    else
      None
  }

  override def getCountMap: Map[T, Int] = bag
    .createEntrySet()
    .asScala
    .map(entry => (entry.getElement, entry.getCount))
    .toMap

  override def allValues: Stream[T] = bag
    .createEntrySet()
    .asScala
    .map(_.getElement)
    .toStream

  override def allValuesSkipping(skipping: MutableMultiset[T]): Stream[T] =
    Core.streamDiff(bag.iterator().asScala.toStream, skipping)
}

/** Implementation using `java.util.concurrent.ConcurrentLinkedQueue`.
  *
  * This is suitable for molecule value types that have a large number of possible values (so that a `Map` storage would be inefficient),
  * or for cases where we do not need to group molecules by value (pipelined molecules).
  */
final class MutableQueueBag[T] extends MutableBag[T] {
  private val bag = new ConcurrentLinkedQueue[T]()

  override def isEmpty: Boolean = bag.isEmpty

  override def size: Int = bag.size

  override def add(v: T): Unit = {
    bag.add(v)
    ()
  }

  override def remove(v: T): Boolean = bag.remove(v)

  override def find(predicate: (T) => Boolean): Option[T] = bag.iterator.asScala.find(predicate)

  override def takeAny(count: Int): Seq[T] = bag.iterator.asScala.take(count).toSeq

  override def takeOne: Option[T] = {
    val iterator = bag.iterator
    if (iterator.hasNext)
      Some(iterator.next)
    else
      None

  }

  // Very inefficient! O(n) operations. Used only for debug output.
  override def getCountMap: Map[T, Int] = bag.iterator.asScala
    .toSeq
    .groupBy(identity)
    .mapValues(_.size)

  override def allValues: Stream[T] = bag.iterator.asScala.toStream

  override def allValuesSkipping(skipping: MutableMultiset[T]): Stream[T] =
    Core.streamDiff(allValues, skipping)
}

/** A simple, limited multiset implementation currently only used by [[Core.streamDiff]].
  * - Not thread-safe.
  * - No iterators over values.
  *
  * @tparam T Type of values held by the multiset.
  */
class MutableMultiset[T](bag: mutable.Map[T, Int] = mutable.Map[T, Int]()) {
  def getCountMap: Map[T, Int] = bag.toMap

  def isEmpty: Boolean = bag.isEmpty

  def size: Int = bag.values.sum

  def copyBag: MutableMultiset[T] = new MutableMultiset[T](bag.clone)

  def add(v: T): MutableMultiset[T] = {
    val count = bag.getOrElseUpdate(v, 0)
    bag.update(v, count + 1)
    this
  }

  def add(vs: Seq[T]): MutableMultiset[T] = {
    vs.foreach(add)
    this
  }

  def remove(v: T): MutableMultiset[T] = {
    bag.get(v).foreach { count ⇒
      if (count <= 1) {
        bag.remove(v)
      } else {
        bag.update(v, count - 1)
      }
    }
    this
  }

  def getCount(v: T): Int = bag.getOrElse(v, 0)

  def contains(v: T): Boolean = bag.contains(v)

  override def toString: String = getCountMap.toString
}

/*
class MutableBag[K, V] {

  private val bag: mutable.Map[K, mutable.Map[V, Int]] = mutable.Map.empty

  override def toString: String = bag.toString

  // Used for printing and for deciding reactions.
  def getMap: Map[K, Map[V, Int]] = bag.mapValues(_.toMap).toMap

  // Only used for counting static molecules at initial emission time.
  def getCountMap: Map[K, Int] = bag.mapValues(_.values.sum).toMap

  // Note: This is currently not used by actual code.
  def getCount(k: K): Int = bag.getOrElse(k, mutable.Map()).values.sum

  // Only used in one debugging message.
  def isEmpty: Boolean = bag.isEmpty

  // Only used for pretty-printing the word "molecule" during debugging, and for tests.
  def size: Int = bag.values.map(_.values.sum).sum

  // Note: This is currently not used by actual code.
  def getOne(k: K): Option[V] = bag.get(k).flatMap(_.headOption.map(_._1))

  def addToBag(k: K, v: V): Unit = {
    bag.get(k) match {
      case Some(vs) =>
        val newCount = vs.getOrElse(v, 0) + 1
        vs += (v -> newCount)

      case None => bag += (k -> mutable.Map(v -> 1))
    }
    ()
  }

  def removeFromBag(k: K, v: V): Unit = bag.get(k).foreach { vs =>
    val newCount = vs.getOrElse(v, 1) - 1
    if (newCount == 0)
      vs -= v
    else
      vs += (v -> newCount)
    if (vs.isEmpty) bag -= k
  }
}
*/
/*
// about 30% slower than MutableBag, and not sure we need it, since all operations with molecule bag are synchronized now.
class ConcurrentMutableBag[K,V] {

  private val bagConcurrentMap: ConcurrentMap[K, ConcurrentMap[V, Int]] = new ConcurrentHashMap()

  override def toString: String = bagConcurrentMap.asScala.toString

  def getMap: Map[K, Map[V, Int]] = bagConcurrentMap.asScala.toMap.mapValues(_.asScala.toMap)

  def getCount(k: K): Int = getMap.getOrElse(k, Map()).values.sum

  def size: Int = getMap.values.map(_.values.sum).sum

  def getOne(k: K): Option[V] = getMap.get(k).flatMap(_.headOption.map(_._1))

  def addToBag(k: K, v: V): Unit = {
    if (bagConcurrentMap.containsKey(k)) {
      val vs = bagConcurrentMap.get(k)
      val newCount = vs.getOrDefault(v, 0) + 1
      vs.put(v, newCount)
    } else {
      bagConcurrentMap.put(k, new ConcurrentHashMap[V, Int](Map(v -> 1).asJava))
    }
    ()
  }

  def removeFromBag(k: K, v: V): Unit = if (bagConcurrentMap.containsKey(k)) {
    val vs = bagConcurrentMap.get(k)
    val newCount = vs.getOrDefault(v, 1) - 1
    if (newCount == 0)
      vs.remove(v)
    else
      vs.put(v, newCount)
    if (vs.isEmpty)
      bagConcurrentMap.remove(k)
    ()
  }

  def removeFromBag(anotherBag: mutable.Map[K,V]): Unit =
    anotherBag.foreach { case (k, v) => removeFromBag(k, v) }

}

*/
// previous implementation - becomes slow if we have many repeated values, fails performance test
/*
class MutableBag[K,V] {

  private val bag: mutable.Map[K, mutable.ArrayBuffer[V]] = mutable.Map.empty

  override def toString = bag.toString

  def getMap: Map[K, Map[V, Int]] = bag.toMap.mapValues(_.groupBy(identity).mapValues(_.size))

  def size: Int = bag.values.map(_.size).sum

  def getOne(k: K): Option[V] = bag.get(k).flatMap(_.headOption)

  def addToBag(k: K, v: V): Unit = bag.get(k) match {
    case Some(vs) => vs += v
    //bag += (k -> vs.+:(v))  4x slower
    case None => bag += (k -> mutable.ArrayBuffer(v))
  }

  def removeFromBag(k: K, v: V): Unit = bag.get(k).foreach { vs =>
    //    val newVs = vs.difff(Seq(v)) 4x slower
    //    if (newVs.isEmpty)
    //      bag -= k
    //    else
    //      bag += (k -> newVs)
    vs -= v
    if (vs.isEmpty)
      bag -= k
  }

  def removeFromBag(another: mutable.Map[K,V]): Unit =
    another.foreach { case (k, v) => removeFromBag(k, v) }

}
*/
