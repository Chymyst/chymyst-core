package io.chymyst.util

// Exploration of final tagless types. This implements Option[T] as a final tagless type.
// Results are not satisfactory: FTOption[T] cannot avoid allocations because it has a type parameter T and thus cannot have `val` implicits.
// We will not use this for now.

object FinalTagless {

  trait FTOptionAlg[T, X] {
    def some(t: T): X

    def none: X
  }

  /* An FTOption value is a function of type FTOptionAlg[T, X] ⇒ X. All it can do is to call methods on its FTOptionAlg argument.
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

// second attempt: try to reduce boilerplate here
object FT2 {

  // Put the ADT definition (all constructor types) here.
  trait FTOptionAlg[T, X] { // The parameter X will be equal to the representing type for all the extra methods.
    def some(t: T): X

    def none: X
  }

  // Type alias for convenience. This is the actual type of the "final tagless option" values.
  type FTOption[T, X] = FTOptionAlg[T, X] ⇒ X

  // Define each constructor now.

  def some[T, X](t: T): FTOption[T, X] = _.some(t)

  def none[T, X]: FTOption[T, X] = _.none

  // For each extra method, define a class that implements constructors, and provide an instance of the class.
  // Note: all extra methods are assumed to have the type Option[T] ⇒ Y and then their class extends FTOptionAlg[T, Y].
  // In other words, Y is the representing type of the extra method.
  class FTIsEmpty[T] extends FTOptionAlg[T, Boolean] {
    def some(t: T): Boolean = false

    def none: Boolean = true
  }

  def ie[T] = new FTIsEmpty[T]

  class FTGetOrElse[T] extends FTOptionAlg[T, T ⇒ T] {
    def some(t: T): T ⇒ T = _ ⇒ t

    def none: T ⇒ T = identity
  }

  def goe[T] = new FTGetOrElse[T]

  // Also, put all extra methods here.
  implicit class FTOptionMethodIsEmpty[T](val fto: FTOption[T, Boolean]) extends AnyVal {
    def isEmpty: Boolean = fto(ie[T])
  }

  implicit class FTOptionMethodGetOrElse[T](val fto: FTOption[T, T ⇒ T]) extends AnyVal {
    def getOrElse(t: T): T = fto(goe[T])(t)
  }

}

// third attempt: try to avoid explicit typing such as some[Int, Int => Int](3).getOrElse(2). Unsuccessful.
object FT3 {

  // Put the ADT definition (all constructor types) here.
  trait FTOptionAlg[T] { // Try to move X into the existential quantifier inside.

    type P

    def some(t: T): P

    def none: P
  }

  // Type alias for convenience. This is the actual type of the "final tagless option" values.
  type FTOption[T, X] = FTOptionAlg[T] {type P = X} ⇒ X

  // Define each constructor now.

  def some[T, X](t: T): FTOption[T, X] = ftoa ⇒ ftoa.some(t)

  def none[T, X]: FTOption[T, X] = _.none

  // For each extra method, define a class that implements constructors, and provide an instance of the class.
  // Note: all extra methods are assumed to have the type Option[T] ⇒ Y and then their class extends FTOptionAlg[T, Y].
  class FTIsEmpty[T] extends FTOptionAlg[T] {
    type P = Boolean

    def some(t: T): Boolean = false

    def none: Boolean = true
  }

  def ie[T] = new FTIsEmpty[T]

  class FTGetOrElse[T] extends FTOptionAlg[T] {

    type P = T ⇒ T

    def some(t: T): T ⇒ T = _ ⇒ t

    def none: T ⇒ T = identity
  }

  def goe[T] = new FTGetOrElse[T]

  // Also, put all extra methods here.
  implicit class FTOptionMethodIsEmpty[T](val fto: FTOption[T, Boolean]) extends AnyVal {
    def isEmpty: Boolean = fto(ie[T])
  }

  implicit class FTOptionMethodGetOrElse[T](val fto: FTOption[T, T ⇒ T]) extends AnyVal {
    def getOrElse(t: T): T = fto(goe[T])(t)
  }

}

//fourth attempt: try to avoid explicit typing such as some[Int, Int => Int](3).getOrElse(2). Success!
object FT4 {

  // Put the ADT definition (all constructor types) here.
  trait FTOptionAlg[T, X] {
    def some(t: T): X

    def none: X
  }

  // This is the actual type of the "final tagless option" values.
  trait FTOption[T] {
    def alg[X](ftoa: FTOptionAlg[T, X]): X
  }

  // Define each constructor now.

  def some[T](t: T): FTOption[T] = new FTOption[T] {
    override def alg[X](ftoa: FTOptionAlg[T, X]): X = ftoa.some(t)
  }

  def none[T]: FTOption[T] = new FTOption[T] {
    override def alg[X](ftoa: FTOptionAlg[T, X]): X = ftoa.none
  }

  // For each extra method, define a class that implements constructors, and provide an instance of the class.
  // Note: all extra methods are assumed to have the type Option[T] ⇒ Y and then their class extends FTOptionAlg[T, Y].
  class FTIsEmpty[T] extends FTOptionAlg[T, Boolean] {
    def some(t: T): Boolean = false

    def none: Boolean = true
  }

  class FTGetOrElse[T] extends FTOptionAlg[T, T ⇒ T] {
    override def some(t: T): T ⇒ T = _ ⇒ t

    def none: T ⇒ T = identity
  }

  // Also, put all extra methods here.
  implicit class FTOptionMethodIsEmpty[T](val fto: FTOption[T]) extends AnyVal {
    def isEmpty: Boolean = fto.alg(new FTIsEmpty[T])
  }

  implicit class FTOptionMethodGetOrElse[T](val fto: FTOption[T]) extends AnyVal {
    def getOrElse(t: T): T = fto.alg(new FTGetOrElse[T])(t)
  }

}

//fifth attempt: try to further reduce boilerplate by using direct function types
object FT5 {

  // Put the ADT definition (all constructor types) here. This is the representing object of the ADT.
  /*
  data Option t where
     some :: t => Option t
     none :: Option t
  */
  trait FTOptionAlg[T, X] {
    def some: T ⇒ X

    def none: X
  }

  // This is the actual type of the "final tagless option" values. Pure boilerplate.
  trait FTOption[T] {
    def alg[X]: FTOptionAlg[T, X] ⇒ X
  }

  // Define each constructor now. Pure boilerplate.
  def some[T](t: T): FTOption[T] = new FTOption[T] {
    def alg[X] = _.some(t)
  }

  def none[T]: FTOption[T] = new FTOption[T] {
    def alg[X] = _.none
  }

  // For each extra method, define a class that implements constructors, and provide an instance of the class.
  // Note: all extra methods are must have the type Option[T] ⇒ Y and then their class extends FTOptionAlg[T, Y].
  class FTIsEmpty[T] extends FTOptionAlg[T, Boolean] {
    def some = _ ⇒ false

    def none = true
  }

  class FTGetOrElse[T] extends FTOptionAlg[T, T ⇒ T] {
    def some: T ⇒ T ⇒ T = t ⇒ _ ⇒ t

    def none: T ⇒ T = identity
  }

  // Also, put all extra methods here. Pure boilerplate.
  implicit class FTOptionMethodIsEmpty[T](val fto: FTOption[T]) extends AnyVal {
    def isEmpty: Boolean = fto.alg(new FTIsEmpty[T])
  }

  implicit class FTOptionMethodGetOrElse[T](val fto: FTOption[T]) extends AnyVal {
    def getOrElse(t: T): T = fto.alg(new FTGetOrElse[T])(t)
  }

}
