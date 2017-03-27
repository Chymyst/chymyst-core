package io.chymyst.jc

/** Helper functions that perform computations with Boolean formulas while keeping them in the conjunctive normal form.
  *
  * A Boolean formula in CNF is represented by a list of lists of an arbitrary type `T`.
  * For instance, the Boolean formula (a || b) && (c || d || e) is represented as
  * {{{ List( List(a, b), List(c, d, e) ) }}}
  *
  * These helper methods will compute disjunction, conjunction, and negation of Boolean formulas in CNF, outputting the results also in CNF.
  * Simplifications are performed only in so far as to remove exact duplicate terms or clauses such as `a || a` or `(a || b) && (a || b)`.
  *
  * The type `T` represents primitive Boolean terms that cannot be further simplified or factored to CNF.
  * These terms could be represented by expression trees or in another way; the CNF computations do not depend on the representation of terms.
  *
  * Note that negation such as `! a` is considered to be a primitive term.
  * Negation of a conjunction or disjunction, such as `! (a || b)`, can be simplified to CNF.
  */
object ConjunctiveNormalForm {

  type CNF[T] = List[List[T]]

  /** Compute `a || b` where `a` is a single Boolean term and `b` is a Boolean formula in CNF.
    *
    * @param a Primitive Boolean term that cannot be simplified; does not contain disjunctions or conjunctions.
    * @param b A Boolean formula in CNF.
    * @tparam T Type of primitive Boolean terms.
    * @return The resulting Boolean formula in CNF.
    */
  def disjunctionOneTerm[T](a: T, b: CNF[T]): CNF[T] = b.map(y => (a :: y).distinct).distinct

  /** Compute `a || b` where `a` is a single "clause", i.e. a disjunction of primitive Boolean terms, and `b` is a Boolean formula in CNF.
    *
    * @param a A list of primitive Boolean terms. This list represents a single disjunction clause, e.g. `List(a, b, c)` represents `a || b || c`.
    * @param b A Boolean formula in CNF.
    * @tparam T Type of primitive Boolean terms.
    * @return The resulting Boolean formula in CNF.
    */
  def disjunctionOneClause[T](a: List[T], b: CNF[T]): CNF[T] = b.map(y => (a ++ y).distinct).distinct

  /** Compute `a || b` where `a` and `b` are Boolean formulas in CNF.
    *
    * @param a A Boolean formula in CNF.
    * @param b A Boolean formula in CNF.
    * @tparam T Type of primitive Boolean terms.
    * @return The resulting Boolean formula in CNF.
    */
  def disjunction[T](a: CNF[T], b: CNF[T]): CNF[T] = a.flatMap(x => disjunctionOneClause(x, b)).distinct

  /** Compute `a && b` where `a` and `b` are Boolean formulas in CNF.
    *
    * @param a A Boolean formula in CNF.
    * @param b A Boolean formula in CNF.
    * @tparam T Type of primitive Boolean terms.
    * @return The resulting Boolean formula in CNF.
    */
  def conjunction[T](a: CNF[T], b: CNF[T]): CNF[T] = (a ++ b).distinct

  /** Compute `! a` where `a` is a Boolean formula in CNF.
    *
    * @param a             A Boolean formula in CNF.
    * @param negateOneTerm A function that describes the transformation of a primitive term under negation.
    *                      For instance, `negateOneTerm(a)` should return `! a` in the term's appropriate representation.
    * @tparam T Type of primitive Boolean terms.
    * @return The resulting Boolean formula in CNF.
    */
  def negation[T](negateOneTerm: T => T)(a: CNF[T]): CNF[T] = a match {
    case x :: xs =>
      val nxs = negation(negateOneTerm)(xs)
      x.flatMap(t => disjunctionOneTerm(negateOneTerm(t), nxs))
    case Nil => List(List()) // negation of true is false
  }

  /** Represents the constant `true` value in CNF. */
  def trueConstant[T]: CNF[T] = List()

  /** Represents the constant `false` value in CNF. */
  def falseConstant[T]: CNF[T] = List(List())

  /** Injects a single primitive term into a CNF. */
  def oneTerm[T](a: T): CNF[T] = List(List(a))
}