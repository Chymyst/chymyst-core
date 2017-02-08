package io.chymyst.jc


import java.util.concurrent.ConcurrentLinkedQueue

import scala.collection.JavaConverters.{asScalaIteratorConverter, asScalaSetConverter}
import Core._

import collection.mutable
import com.google.common.collect.ConcurrentHashMultiset

/*
class MutableBag[K,V] { // quadratic time, extremely slow
  val bag: mutable.Map[K, mutable.DoubleLinkedList[V]] = mutable.Map.empty

  def get(k: K): Option[mutable.DoubleLinkedList[V]] = bag.get(k)

  def addToBag(k: K, v: V): Unit = bag.get(k) match {
    case Some(vs) => bag += (k -> (vs.+:(v)))// v
    case None => bag += (k -> mutable.DoubleLinkedList(v))
  }

  def removeFromBag(k: K, v: V): Unit = bag.get(k).foreach { vs =>
    val newVs = vs.difff(Seq(v))
    if (newVs.isEmpty)
      bag -= k
    else
      bag += (k -> newVs)
  }

  def removeFromBag(another: mutable.Map[K,V]): Unit =
    another.foreach { case (k, v) => removeFromBag(k, v) }

}
*/

/** Abstract container for molecule values. Concrete implementations may optimize for specific molecule types.
  *
  * @tparam T Type of the value carried by molecule.
  */
sealed trait MolValueBag[T] {
  def count(v: T): Int

  def size: Int

  def add(v: T): MolValueBag[T]

  def remove(v: T): MolValueBag[T]

  def find(predicate: T => Boolean): Option[T]
}

//object MolValueBag {
//  def of[T](v: T): MolValueBag[T] = {
//    new MolValueMapBag[T]
//      .add(v)
//  }
//}

/** Implementation using guava's [[ConcurrentHashMultiset]].
  *
  * This is suitable for types that have a small number of possible values (i.e. [[simpleTypes]]).
  */
final class MolValueMapBag[T] extends MolValueBag[T] {
  private val bag: ConcurrentHashMultiset[T] = ConcurrentHashMultiset.create()

  override def count(v: T): Int = bag.count(v)

  override def size: Int = bag.size

  override def add(v: T): MolValueBag[T] = {
    bag.add(v, 1)
    this
  }

  override def remove(v: T): MolValueBag[T] = {
    bag.remove(v)
    this
  }

  override def find(predicate: (T) => Boolean): Option[T] =
    bag.createEntrySet().asScala
      .map(_.getElement)
      .find(predicate)
}

/** Implementation using guava's [[ConcurrentHashMultiset]].
  *
  * This is suitable for types that have a small number of possible values (i.e. [[simpleTypes]]).
  */
final class MolValueQueueBag[T] extends MolValueBag[T] {
  private val bag: ConcurrentLinkedQueue[T] = new ConcurrentLinkedQueue[T]()

  override def count(v: T): Int = bag.iterator.asScala.count(_ === v)

  override def size: Int = bag.size

  override def add(v: T): MolValueBag[T] = {
    bag.add(v)
    this
  }

  override def remove(v: T): MolValueBag[T] = {
    bag.remove(v)
    this
  }

  override def find(predicate: (T) => Boolean): Option[T] = bag.iterator.asScala.find(predicate)
}

// currently used implementation
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
