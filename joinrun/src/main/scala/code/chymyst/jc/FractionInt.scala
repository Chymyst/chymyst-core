package code.chymyst.jc
/*
import scala.annotation.tailrec

final case class FractionInt(numerator: Int, denominator: Int) {
  def +(y: FractionInt): FractionInt = {
    val gcd = FractionInt.gcd(denominator, y.denominator)
    FractionInt((numerator * y.denominator + denominator * y.numerator) / gcd, FractionInt.lcm(denominator, y.denominator))
  }

  def *(y: FractionInt): FractionInt = {
    val gcd = FractionInt.gcd(denominator, y.denominator)
    FractionInt(numerator * y.numerator / gcd, denominator * y.denominator / gcd)
  }

  def unary_- : FractionInt = FractionInt(-numerator, denominator)

  def -(y: FractionInt): FractionInt = this.+(-y)

  def inverse: FractionInt = FractionInt(denominator, numerator)
}

object FractionInt extends Fractional[FractionInt] {

  @tailrec
  def gcd(a: Int, b: Int): Int =
    if (b == 0) a else gcd(b, a % b)

  def lcm(a: Int, b: Int): Int = {
    val product = a * b
    if (product == 0) 0 else product / gcd(a, b)
  }

  override def div(x: FractionInt, y: FractionInt): FractionInt = x * y.inverse

  override def plus(x: FractionInt, y: FractionInt): FractionInt = x + y

  override def minus(x: FractionInt, y: FractionInt): FractionInt = x - y

  override def times(x: FractionInt, y: FractionInt): FractionInt = x * y

  override def negate(x: FractionInt): FractionInt = -x

  override def fromInt(x: Int): FractionInt = FractionInt(x, 1)

  override def toInt(x: FractionInt): Int = x.numerator / x.denominator

  override def toLong(x: FractionInt): Long = (x.numerator / x.denominator).toLong

  override def toFloat(x: FractionInt): Float = x.numerator.toFloat / x.denominator.toFloat

  override def toDouble(x: FractionInt): Double = x.numerator.toDouble / x.denominator.toDouble

  override def compare(x: FractionInt, y: FractionInt): Int = (x.numerator * y.denominator) compare (y.numerator * x.denominator)
}
*/
