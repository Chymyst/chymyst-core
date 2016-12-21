package code.winitzki.jc

private[jc] object JoinRunUtils {

  private lazy val sha1Digest = java.security.MessageDigest.getInstance("SHA-1")

  def getSha1(c: Any): String = sha1Digest.digest(c.toString.getBytes("UTF-8")).map("%02X".format(_)).mkString

//  def flatten[T](optionSet: Option[Set[T]]): Set[T] = optionSet.getOrElse(Set())
//  def flatten[T](optionSeq: Option[Seq[T]]): Seq[T] = optionSeq.getOrElse(Seq())

  trait PersistentHashCode {
    // Make hash code persistent across mutations with this simple trick.
    private lazy val hashCodeValue: Int = super.hashCode()
    override def hashCode(): Int = hashCodeValue
  }

  /** Add a random shuffle method to sequences.
    *
    * @param a Sequence to be shuffled.
    * @tparam T Type of sequence elements.
    */
  implicit final class ShufflableSeq[T](a: Seq[T]) {
    /** Shuffle sequence elements randomly.
      *
      * @return A new sequence with randomly permuted elements.
      */
    def shuffle: Seq[T] = scala.util.Random.shuffle(a)
  }

  def nonemptyOpt[S](s: Seq[S]): Option[Seq[S]] = if (s.isEmpty) None else Some(s)

  @SuppressWarnings(Array("org.wartremover.warts.Equals"))
  implicit final class AnyOpsEquals[A](self: A) {
    def ===(other: A): Boolean = self == other
  }

  @SuppressWarnings(Array("org.wartremover.warts.Equals"))
  implicit final class AnyOpsNotEquals[A](self: A) {
    def =!=(other: A): Boolean = self != other
  }

}
