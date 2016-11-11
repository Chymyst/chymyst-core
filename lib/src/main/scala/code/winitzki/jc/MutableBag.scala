package code.winitzki.jc

import scala.collection.mutable

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
/*
class MutableBag[K,V] {

  private val bag: mutable.Map[K, mutable.Map[V, Int]] = mutable.Map.empty

  override def toString = bag.toString

  def getMap: Map[K, Map[V, Int]] = bag.toMap.mapValues(_.toMap)

  def size: Int = bag.values.map(_.values.sum).sum

  def getOne(k: K): Option[V] = bag.get(k).flatMap(_.headOption.map(_._1))

  def addToBag(k: K, v: V): Unit = bag.get(k) match {
    case Some(vs) =>
      val newCount = vs.getOrElse(v, 0) + 1
      vs += (v -> newCount)

    case None => bag += (k -> mutable.Map(v -> 1))
  }

  def removeFromBag(k: K, v: V): Unit = bag.get(k).foreach { vs =>
    val newCount = vs.getOrElse(v, 1) - 1
    if (newCount == 0)
      vs -= v
    else
      vs += (v -> newCount)
    if (vs.isEmpty) bag -= k
  }

  def removeFromBag(another: mutable.Map[K,V]): Unit =
    another.foreach { case (k, v) => removeFromBag(k, v) }

}
*/
// previous implementation - becomes slow if we have many repeated values, fails performance test
/* */
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
/* */