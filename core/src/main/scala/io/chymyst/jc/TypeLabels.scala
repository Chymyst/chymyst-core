package io.chymyst.jc

import scala.language.higherKinds

object TypeLabels {

  sealed trait LabelX[X] {
    type T <: X

    def apply(s: X): T

    def subst[F[_]](fs: F[X]): F[T]
  }

  def makeLabeledX[X]: LabelX[X] = new LabelX[X] {
    type T = X

    override def apply(s: X): T = s

    override def subst[F[_]](fs: F[X]): F[T] = fs
  }

  // Example type label for String. See https://failex.blogspot.com/2017/04/the-high-cost-of-anyval-subclasses.html
  object LabeledString {

    sealed abstract class LabelImpl {
      type T <: String

      def apply(s: String): T

      def subst[F[_]](fs: F[String]): F[T]
    }

    // do not forget `: LabelImpl`; it is key
    val Label: LabelImpl = new LabelImpl {
      type T = String

      override def apply(s: String): T = s

      override def subst[F[_]](fs: F[String]): F[T] = fs
    }

    type Label = Label.T
  }

  /* Example usage:
   * import LabeledString._
   * val x = Label("abc")  // x is now of type Label
   * val y: String = x // y is now again a String
   */

  /* Example macro usage:
   * val LabeledString = typelabel[String](object LabeledString { type Label })
   *
   * import LabeledString._
   * val x = Label("abc") // x is now of type LabeledString.Label
   */
}
