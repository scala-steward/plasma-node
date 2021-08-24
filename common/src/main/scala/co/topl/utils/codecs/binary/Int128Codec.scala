package co.topl.utils.codecs.binary

import cats.implicits._
import co.topl.utils.Int128

import scala.annotation.tailrec

object Int128Codec {

  /**
   * Recursive helper method for decoding an `Int128` value from a list of bytes.
   *
   * @param currentBytes the current list of bytes which will be converted into an `Int128` value
   * @param remainingBytes the remaining list of bytes to parse
   * @param iteration the current iteration of parsing
   * @return if successful, a decoded `Int128` value and the remaining non-decoded bytes
   */
  @tailrec
  private def decodeHelper(
    currentBytes:   Array[Byte],
    remainingBytes: LazyList[Byte],
    iteration:      Int
  ): DecoderResult[Int128] =
    if (iteration >= Int128.numBytes) (Int128(currentBytes), remainingBytes).asRight
    else
      remainingBytes match {
        case head #:: tail => decodeHelper(currentBytes :+ head, tail, iteration + 1)
        case _             => ParseFailure.asLeft
      }

  /**
   * Decodes an `Int128` value from a lazy list of bytes.
   * @param from the list of bytes to decode a `Int128` value from
   * @return if successful, a decoded `Int128` value and the remaining non-decoded bytes
   */
  def decode(from: LazyList[Byte]): DecoderResult[Int128] =
    decodeHelper(currentBytes = Array(), remainingBytes = from, iteration = 0)

  trait Implicits {
    implicit val lazyInt128Decoder: LazyBytesDecoder[Int128] = decode
  }
}
