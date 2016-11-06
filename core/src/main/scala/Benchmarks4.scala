package sample

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import JoinDef._

object Benchmarks4 {
  def benchmark4_100(count: Int, threads: Int = 2): Long = {
    val initialTime = LocalDateTime.now
    val n = 100
    val g = {
      val is: IndexedSeq[Int] = 0 until n
      val f = ja[Unit]("f")
      val g = js[LocalDateTime, Long]("g")
      val as = is.map(i => ja[Int](s"a$i"))
      val jrs = IndexedSeq(
        run { case f(_) & g(initTime, r) => r(initTime.until(LocalDateTime.now, ChronoUnit.MILLIS)) }
      ) ++ is.map(
        i => {
          val a = as(i)
          val b = if (i == n-1) as(0) else as(i + 1)
          if (i == n-1)
            run { case a(m) => if (m == 0) f() else b(m - 1) }
          else
            run { case a(m) => b(m) }
        }
      )
      joindef(jrs: _*)
      as(0)(count)
      g
    }
    g(initialTime)
  }

  def benchmark5_6(count: Int, threads: Int = 2): Long = {

    object j5 extends Join {
      object f extends AsyName[Unit]
      object g extends SynName[LocalDateTime, Long]
      object a0 extends AsyName[Int]
      object a1 extends AsyName[Int]
      object a2 extends AsyName[Int]
      object a3 extends AsyName[Int]
      object a4 extends AsyName[Int]
      object a5 extends AsyName[Int]
      join {
        case f(_) and g(tInit) =>
          g.reply(tInit.until(LocalDateTime.now, ChronoUnit.MILLIS))
        case a0(n) => a1(n)
        case a1(n) => a2(n)
        case a2(n) => a3(n)
        case a3(n) => a4(n)
        case a4(n) => a5(n)
        case a5(m) => if (m==0) f() else a0(m-1)
      }
      a0(count)

    }
    val initialTime = LocalDateTime.now

    j5.g(initialTime)

  }

  def benchmark5_100(count: Int, threads: Int = 2): Long = {

    object j5_100 extends Join {
      object f extends AsyName[Unit]
      object g extends SynName[LocalDateTime, Long]
      object a0 extends AsyName[Int]
      object a1 extends AsyName[Int]
      object a2 extends AsyName[Int]
      object a3 extends AsyName[Int]
      object a4 extends AsyName[Int]
      object a5 extends AsyName[Int]
      object a6 extends AsyName[Int]
      object a7 extends AsyName[Int]
      object a8 extends AsyName[Int]
      object a9 extends AsyName[Int]
      object a10 extends AsyName[Int]
      object a11 extends AsyName[Int]
      object a12 extends AsyName[Int]
      object a13 extends AsyName[Int]
      object a14 extends AsyName[Int]
      object a15 extends AsyName[Int]
      object a16 extends AsyName[Int]
      object a17 extends AsyName[Int]
      object a18 extends AsyName[Int]
      object a19 extends AsyName[Int]
      object a20 extends AsyName[Int]
      object a21 extends AsyName[Int]
      object a22 extends AsyName[Int]
      object a23 extends AsyName[Int]
      object a24 extends AsyName[Int]
      object a25 extends AsyName[Int]
      object a26 extends AsyName[Int]
      object a27 extends AsyName[Int]
      object a28 extends AsyName[Int]
      object a29 extends AsyName[Int]
      object a30 extends AsyName[Int]
      object a31 extends AsyName[Int]
      object a32 extends AsyName[Int]
      object a33 extends AsyName[Int]
      object a34 extends AsyName[Int]
      object a35 extends AsyName[Int]
      object a36 extends AsyName[Int]
      object a37 extends AsyName[Int]
      object a38 extends AsyName[Int]
      object a39 extends AsyName[Int]
      object a40 extends AsyName[Int]
      object a41 extends AsyName[Int]
      object a42 extends AsyName[Int]
      object a43 extends AsyName[Int]
      object a44 extends AsyName[Int]
      object a45 extends AsyName[Int]
      object a46 extends AsyName[Int]
      object a47 extends AsyName[Int]
      object a48 extends AsyName[Int]
      object a49 extends AsyName[Int]
      object a50 extends AsyName[Int]
      object a51 extends AsyName[Int]
      object a52 extends AsyName[Int]
      object a53 extends AsyName[Int]
      object a54 extends AsyName[Int]
      object a55 extends AsyName[Int]
      object a56 extends AsyName[Int]
      object a57 extends AsyName[Int]
      object a58 extends AsyName[Int]
      object a59 extends AsyName[Int]
      object a60 extends AsyName[Int]
      object a61 extends AsyName[Int]
      object a62 extends AsyName[Int]
      object a63 extends AsyName[Int]
      object a64 extends AsyName[Int]
      object a65 extends AsyName[Int]
      object a66 extends AsyName[Int]
      object a67 extends AsyName[Int]
      object a68 extends AsyName[Int]
      object a69 extends AsyName[Int]
      object a70 extends AsyName[Int]
      object a71 extends AsyName[Int]
      object a72 extends AsyName[Int]
      object a73 extends AsyName[Int]
      object a74 extends AsyName[Int]
      object a75 extends AsyName[Int]
      object a76 extends AsyName[Int]
      object a77 extends AsyName[Int]
      object a78 extends AsyName[Int]
      object a79 extends AsyName[Int]
      object a80 extends AsyName[Int]
      object a81 extends AsyName[Int]
      object a82 extends AsyName[Int]
      object a83 extends AsyName[Int]
      object a84 extends AsyName[Int]
      object a85 extends AsyName[Int]
      object a86 extends AsyName[Int]
      object a87 extends AsyName[Int]
      object a88 extends AsyName[Int]
      object a89 extends AsyName[Int]
      object a90 extends AsyName[Int]
      object a91 extends AsyName[Int]
      object a92 extends AsyName[Int]
      object a93 extends AsyName[Int]
      object a94 extends AsyName[Int]
      object a95 extends AsyName[Int]
      object a96 extends AsyName[Int]
      object a97 extends AsyName[Int]
      object a98 extends AsyName[Int]
      object a99 extends AsyName[Int]

      join {
        case f(_) and g(tInit) =>
          g.reply(tInit.until(LocalDateTime.now, ChronoUnit.MILLIS))

        case a0(n) => a1(n);
        case a1(n) => a2(n);
        case a2(n) => a3(n);
        case a3(n) => a4(n);
        case a4(n) => a5(n);
        case a5(n) => a6(n);
        case a6(n) => a7(n);
        case a7(n) => a8(n);
        case a8(n) => a9(n);
        case a9(n) => a10(n);
        case a10(n) => a11(n)
        case a11(n) => a12(n)
        case a12(n) => a13(n)
        case a13(n) => a14(n)
        case a14(n) => a15(n)
        case a15(n) => a16(n)
        case a16(n) => a17(n)
        case a17(n) => a18(n)
        case a18(n) => a19(n)
        case a19(n) => a20(n)
        case a20(n) => a21(n)
        case a21(n) => a22(n)
        case a22(n) => a23(n)
        case a23(n) => a24(n)
        case a24(n) => a25(n)
        case a25(n) => a26(n)
        case a26(n) => a27(n)
        case a27(n) => a28(n)
        case a28(n) => a29(n)
        case a29(n) => a30(n)
        case a30(n) => a31(n)
        case a31(n) => a32(n)
        case a32(n) => a33(n)
        case a33(n) => a34(n)
        case a34(n) => a35(n)
        case a35(n) => a36(n)
        case a36(n) => a37(n)
        case a37(n) => a38(n)
        case a38(n) => a39(n)
        case a39(n) => a40(n)
        case a40(n) => a41(n)
        case a41(n) => a42(n)
        case a42(n) => a43(n)
        case a43(n) => a44(n)
        case a44(n) => a45(n)
        case a45(n) => a46(n)
        case a46(n) => a47(n)
        case a47(n) => a48(n)
        case a48(n) => a49(n)
        case a49(n) => a50(n)
        case a50(n) => a99(n)
//        case a51(n) => a52(n)
//        case a52(n) => a53(n)
//        case a53(n) => a54(n)
//        case a54(n) => a55(n)
//        case a55(n) => a56(n)
//        case a56(n) => a57(n)
//        case a57(n) => a58(n)
//        case a58(n) => a59(n)
//        case a59(n) => a60(n)
//        case a60(n) => a61(n)
//        case a61(n) => a62(n)
//        case a62(n) => a63(n)
//        case a63(n) => a64(n)
//        case a64(n) => a65(n)
//        case a65(n) => a66(n)
//        case a66(n) => a67(n)
//        case a67(n) => a68(n)
//        case a68(n) => a69(n)
//        case a69(n) => a70(n)
//        case a70(n) => a71(n)
//        case a71(n) => a72(n)
//        case a72(n) => a73(n)
//        case a73(n) => a74(n)
//        case a74(n) => a75(n)
//        case a75(n) => a76(n)
//        case a76(n) => a77(n)
//        case a77(n) => a78(n)
//        case a78(n) => a79(n)
//        case a79(n) => a80(n)
//        case a80(n) => a81(n)
//        case a81(n) => a82(n)
//        case a82(n) => a83(n)
//        case a83(n) => a84(n)
//        case a84(n) => a85(n)
//        case a85(n) => a86(n)
//        case a86(n) => a87(n)
//        case a87(n) => a88(n)
//        case a88(n) => a89(n)
//        case a89(n) => a90(n)
//        case a90(n) => a91(n)
//        case a91(n) => a92(n)
//        case a92(n) => a93(n)
//        case a93(n) => a94(n)
//        case a94(n) => a95(n)
//        case a95(n) => a96(n)
//        case a96(n) => a97(n)
//        case a97(n) => a98(n)
//        case a98(n) => a99(n)

        case a99(m) => if (m==0) f() else a0(m-1)
      }
      a0(count)

    }
    val initialTime = LocalDateTime.now

    j5_100.g(initialTime)

  }

}