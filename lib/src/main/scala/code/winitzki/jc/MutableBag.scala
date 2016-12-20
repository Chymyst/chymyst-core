package code.winitzki.jc

import collection.JavaConverters._
import java.util.concurrent.{ConcurrentHashMap, ConcurrentMap}

import collection.mutable

/*
class MutableBag[K,V] { // quadratic time, extremely slow
  val bag: mutable.Map[K, mutable.DoubleLinkedList[V]] = mutable.Map.empty

  def get(k: K): Option[mutable.DoubleLinkedList[V]] = bag.get(k)

  def addToBag(k: K, v: V): Unit = bag.get(k) match {
    case Some(vs) => bag += (k -> (vs.+:(v)))// v
    case None => bag += (k -> mutable.DoubleLinkedList(v))
  }

  def removeFromBag(k: K, v: V): Unit = bag.get(k).foreach { vs =>
    val newVs = vs.diff(Seq(v))
    if (newVs.isEmpty)
      bag -= k
    else
      bag += (k -> newVs)
  }

  def removeFromBag(another: mutable.Map[K,V]): Unit =
    another.foreach { case (k, v) => removeFromBag(k, v) }

}
*/
/* */

object MutableBag {

  def of[K, V](k: K, v: V): MutableBag[K, V] = {
    val b = new MutableBag[K,V]
    b.addToBag(k, v)
    b
  }
}

class MutableBag[K,V] {

  private val bag: mutable.Map[K, mutable.Map[V, Int]] = mutable.Map.empty

  override def toString = bag.toString

  def getMap: Map[K, Map[V, Int]] = bag.mapValues(_.toMap).toMap

  def getCountMap: Map[K, Int] = bag.mapValues(_.values.sum).toMap

  def getCount(k: K): Int = bag.getOrElse(k, mutable.Map()).values.sum

  def size: Int = bag.values.map(_.values.sum).sum

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

  def removeFromBag(anotherBag: mutable.Map[K,V]): Unit =
    anotherBag.foreach { case (k, v) => removeFromBag(k, v) }

}
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

/* */
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
    //    val newVs = vs.diff(Seq(v)) 4x slower
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
