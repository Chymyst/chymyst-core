package code.winitzki.jc

import code.winitzki.jc.JoinRun._

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.reflect.ClassTag

object Library {
  /** Create an output molecule that, when injected, will resolve the future.
    *
    * @tparam T Type of value carried by the molecule and by the future.
    * @return Tuple of molecule injector and the future
    */
  def moleculeFuture[T : ClassTag]: (JA[T], Future[T]) = {
    val f = ja[T]
    val p = Promise[T]()

    join(
      & { case f(x) => p.success(x) }
    )
    (f, p.future)
  }

  implicit class FutureWithMolecule[T](f: Future[T])(implicit ec: ExecutionContext) {
    /** Modify the future: when it succeeds, it will additionally inject a given molecule.
      * The value on the molecule will be equal to the result value of the future.
      * (The result value of the future is unchanged.)
      *
      * @param m Molecule injector, must have the same type as the future.
      * @return The modified future.
      */
    def &(m: JA[T]): Future[T] = f map { x =>
      m(x)
      x
    }

    /** Modify the future: when it succeeds, it will additionally inject a given molecule.
      * The molecule will carry the specified value (the result value of the future is unchanged).
      *
      * @param u Molecule injection expression, such as a(123)
      * @return The modified future.
      */
    def +(u: => Unit): Future[T] = f map { x =>
      u
      x
    }
  }

}