package io.chymyst.util

import scala.language.higherKinds

trait LabeledType[X] {
  type T

  /** Convert a collection of raw type `X` into a collection of labeled type.
    * This does not iterate over the collection and does not allocate memory.
    *
    * @param xs A collection of raw type.
    * @tparam F The collection's type constructor.
    * @return Collection of labeled type.
    */
  def maptype[F[_]](xs: F[X]): F[T]

  /** Convert a collection of labeled type into a collection of raw type `X`.
    * This does not iterate over the collection and does not allocate memory.
    *
    * @param ts A collection of labeled type.
    * @tparam F The collection's type constructor.
    * @return Collection of raw type.
    */
  def unmaptype[F[_]](ts: F[T]): F[X]

  /** Convert a value of raw type `X` into a labeled type.
    * No memory allocation is performed during this conversion.
    *
    * @param x A value of raw type `X`.
    * @return A value of labeled type.
    */
  def apply(x: X): T

  /** Convert a value of labeled type into a value of the raw type `X`.
    * No memory allocation is performed during this conversion.
    *
    * @param t A value of labeled type.
    * @return A value of corresponding raw type.
    */
  def get(t: T): X
}

trait LabeledSubtype[X] {
  type T <: X

  /** Convert a value of raw type `X` into a labeled type.
    * This does not iterate over the collection and does not allocate memory.
    *
    * @param x A value of raw type `X`.
    * @return A value of labeled type.
    */
  def apply(x: X): T

  /** Convert a collection of raw type `X` into a collection of labeled type.
    *
    * @param xs A collection of raw type.
    * @tparam F The collection's type constructor.
    * @return Collection of labeled type.
    */
  def maptype[F[_]](xs: F[X]): F[T]
}

/** Light-weight type aliases.
  * Unlike type classes, these type aliases never perform any memory allocation at run time.
  */
object LabeledTypes {

  /** Define a type alias for an existing type `X`.
    * Example usage:
    * {{{
    *   val UserName = Newtype[String]
    *   type UserName = UserName.T // optional, for convenience
    *
    *   val x = UserName("user 1") // create a UserName
    *   val length = UserName.get(x).length // need conversion to recover the underlying String
    * }}}
    * The new type `UserName.T` will not be the same as `X` and will not be a subtype of `X`.
    *
    * @tparam X An existing type.
    * @return A constructor for a new type alias.
    */
  def Newtype[X]: LabeledType[X] = new LabeledType[X] {
    type T = X

    def maptype[F[_]](fs: F[X]): F[T] = fs

    def unmaptype[F[_]](fs: F[T]): F[X] = fs

    def apply(x: X): T = x

    def get(lbl: T): X = lbl
  }

  /** Define a subtype alias for an existing type `X`.
    * Example usage:
    * {{{
    *   val UserName = Subtype[String]
    *   type UserName = UserName.T // optional, for convenience
    *
    *   val x = UserName("user 1") // create a UserName
    *   val length = x.length // recover and use the underlying String without conversion
    * }}}
    * The new type `UserName.T` will not be the same as `String` but will be a subtype of `String`.
    * Due to subtyping, conversion from `UserName` back to `String` is immediate and requires no code.
    * Also, conversion of `List[UserName]` back to `List[String]` is immediate.
    *
    * @tparam X An existing type.
    * @return A constructor for a new type alias.
    */
  def Subtype[X]: LabeledSubtype[X] = new LabeledSubtype[X] {
    type T = X

    def maptype[F[_]](fs: F[X]): F[T] = fs

    def apply(x: X): T = x
  }
}
