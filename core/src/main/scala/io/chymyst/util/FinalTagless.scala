package io.chymyst.util

object FinalTagless {

  trait FTOptionAlg[T, X] {
    def some(t: T): X

    def none: X
  }

  /* Example: An FTOption value is a function of type FTOptionAlg[T, X] ⇒ X
  *
  * def some[T, X](t: T)(implicit ftOptionAlg: FTOptionAlg[T, X]): X = ftOptionAlg.some(t)
  * */

  /* We would like to implement the method `isEmpty` on FTOption. */

  trait FTOptionIsEmpty {
    def isEmpty: Boolean
  }

  class FTIsEmpty[T] extends FTOptionAlg[T, FTOptionIsEmpty] {
    override def some(t: T): FTOptionIsEmpty = new FTOptionIsEmpty {
      override def isEmpty: Boolean = false
    }

    override def none: FTOptionIsEmpty = new FTOptionIsEmpty {
      override def isEmpty: Boolean = true
    }
  }

  def ie[T] = new FTIsEmpty[T]

  // concrete values of final-tagless-option type
  def some[T, X](t: T)(fTOptionAlg: FTOptionAlg[T, X]): X = fTOptionAlg.some(t)

  def none[T, X](fTOptionAlg: FTOptionAlg[T, X]): X = fTOptionAlg.none

  /* Now we would like to implement getOrElse */

  trait FTOptionGetOrElse[T] {
    def getOrElse(e: T): T
  }

  class FTGetOrElse[T] extends FTOptionAlg[T, FTOptionGetOrElse[T]] {
    override def some(t: T): FTOptionGetOrElse[T] = new FTOptionGetOrElse[T] {
      override def getOrElse(e: T): T = t
    }

    override def none: FTOptionGetOrElse[T] = new FTOptionGetOrElse[T] {
      override def getOrElse(e: T): T = e
    }
  }

  def goe[T] = new FTGetOrElse[T]


}

