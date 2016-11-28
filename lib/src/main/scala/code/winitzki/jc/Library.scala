package code.winitzki.jc

import code.winitzki.jc.JoinRun._

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.reflect.ClassTag

object Library {
  /** Create a non-blocking molecule that, when injected, will resolve the future.
    * Example usage: val (m, fut) = moleculeFuture[String]
    *
    * @tparam T Type of value carried by the molecule and by the future.
    * @return Tuple consisting of new molecule injector and the new future
    */
  def moleculeFuture[T : ClassTag]: (M[T], Future[T]) = {
    val f = new M[T]("future")
    val p = Promise[T]()

    join(
      runSimple { case f(x) => p.success(x) }
    )
    (f, p.future)
  }

  implicit class FutureWithMolecule[T](f: Future[T])(implicit ec: ExecutionContext) {
    /** Modify the future: when it succeeds, it will additionally inject a given molecule.
      * The value on the molecule will be equal to the result value of the future.
      * (The result value of the future is unchanged.)
      *
      * Example usage: Future { ... } & a
      *
      * @param m Molecule injector, must have the same type as the future.
      * @return The modified future.
      */
    def &(m: M[T]): Future[T] = f map { x =>
      m(x)
      x
    }

    /** Modify the future: when it succeeds, it will additionally inject a given molecule.
      * The molecule will carry the specified value (the result value of the future is unchanged).
      *
      * Example usage: Future { ... } + a(123)
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