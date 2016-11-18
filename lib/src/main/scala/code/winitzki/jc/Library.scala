package code.winitzki.jc

import code.winitzki.jc.JoinRun._

import scala.concurrent.{Future, Promise}
import scala.reflect.ClassTag

object Library {
  def makeFuture[T : ClassTag]: (JA[T], Future[T]) = {
    val f = ja[T]
    val p = Promise[T]()

    join(
      & { case f(x) => p.success(x) }
    )
    (f, p.future)
  }

}