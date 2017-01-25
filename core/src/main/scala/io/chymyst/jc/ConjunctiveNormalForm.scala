package io.chymyst.jc

object ConjunctiveNormalForm {
  def disjunctionOneTerm[T](a: T, b: List[List[T]]): List[List[T]] = b.map(y => (a :: y).distinct).distinct

  def disjunctionOneClause[T](a: List[T], b: List[List[T]]): List[List[T]] = b.map(y => (a ++ y).distinct).distinct

  def disjunction[T](a: List[List[T]], b: List[List[T]]): List[List[T]] = a.flatMap(x => disjunctionOneClause(x, b)).distinct

  def conjunction[T](a: List[List[T]], b: List[List[T]]): List[List[T]] = (a ++ b).distinct

  def negation[T](negationOneTerm: T => T)(a: List[List[T]]): List[List[T]] = a match {
    case x :: xs =>
      val nxs = negation(negationOneTerm)(xs)
      x.flatMap(t => disjunctionOneTerm(negationOneTerm(t), nxs))
    case Nil => List(List()) // negation of true is false
  }

  def trueConstant[T]: List[List[T]] = List()

  def falseConstant[T]: List[List[T]] = List(List())

  def oneTerm[T](a: T): List[List[T]] = List(List(a))
}