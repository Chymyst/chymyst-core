package code.winitzki.jc

object JoinRunUtils {

  private lazy val sha1Digest = java.security.MessageDigest.getInstance("SHA-1")

  def getSha1(c: Any): String = sha1Digest.digest(c.toString.getBytes("UTF-8")).map("%02X".format(_)).mkString

}
