package code.chymyst.jc


sealed trait NonemptyRevList[T] {
  def leaf: T
  def trunk: NonemptyRevList[T]
}

case class Leaf[T](v: T) extends NonemptyRevList[T] {
  def leaf: T = v
  def trunk: NonemptyRevList[T] = this
}

case class Trunk[T](t: NonemptyRevList[T], v: T) extends NonemptyRevList[T] {
  def leaf: T = v
  def trunk: NonemptyRevList[T] = t
}
