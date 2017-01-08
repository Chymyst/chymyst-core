package sample

import scala.language.experimental.macros
import scala.reflect.macros.{blackbox }

case class Blah(left: Int, right: Int)
case class Blob(left: (Int, Option[Int]), right: Int)

case class ResultBlob(res: PartialFunction[Blob,Any])

object MacroTest {

  def rawTree(x: Any): String = macro rawTreeImpl

  def rawTreeImpl(c: blackbox.Context)(x: c.Expr[Any]): c.universe.Tree = {
    import c.universe._
    val result = showRaw(x.tree)
    q"$result"
  }
  type Simple = PartialFunction[Int, Any]
  type Medium = PartialFunction[Blah, Any]
  type Complicated = PartialFunction[Blob, Any]

  def no_problem(pf: Simple): Simple = macro no_problemImpl
  def no_problemImpl(c: blackbox.Context)(pf: c.Expr[Simple]) = {
    import c.universe._
    val q"{ case $binder  => $body }" = pf.tree
    q"{ case $binder  => $body }"
  }

  def par_problem[T](pf: PartialFunction[T,Any]): PartialFunction[T,Any] = macro par_problemImpl[T]
  def par_problemImpl[T](c: blackbox.Context)(pf: c.Expr[PartialFunction[T,Any]]) = {
    import c.universe._
    val q"{ case $binder  => $body }" = pf.tree
    q"{ case $binder  => $body }"
  }

  def medium_problem(pf: Medium): Medium = macro medium_problemImpl
  def medium_problemImpl(c: blackbox.Context)(pf: c.Expr[Medium]) = {
    import c.universe._
    val q"{ case $binder  => $body }" = pf.tree
    q"{ case $binder  => $body }"
  }

  def problem(pf: Complicated): Complicated = macro problemImpl

  def problemImpl(c: blackbox.Context)(pf: c.Expr[Complicated]) = {
    import c.universe._

//    if (false) {
//      // search through the tree of pf and find matchers
//      val q"{ case ..$cases }" = pf.tree
//
//      val caseDef = cases.head
//      val cq"$binder if $guard => $body" = caseDef
//
//      val newRes = q"{ case $binder if $guard => $body }"
//
//      q"ResultBlob($newRes)"
//
//    } else {
      // search through the tree of pf and find matchers

//      val q"{ case $binder  => $body }" = pf.tree
//      val cases = List(cq"$binder => $body")
//      q"ResultBlob({ case ..$cases })"



      val q"{ case $binder  => $body }" = pf.tree
      q"{ case $binder  => $body }"


  }

}