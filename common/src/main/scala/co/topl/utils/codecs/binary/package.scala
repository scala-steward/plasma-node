package co.topl.utils.codecs

import cats.implicits._
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._

import java.nio.charset.{Charset, StandardCharsets}
import scala.annotation.tailrec

import scala.language.implicitConversions

package object binary {

  case object ValidationFailure
  type ValidationFailure = ValidationFailure.type

  case object DecoderFailure
  type DecoderFailure = DecoderFailure.type
  type DecoderResult[T] = Either[DecoderFailure, (T, LazyList[Byte])]

  type ULong = Long

  val stringCharacterSet: Charset = StandardCharsets.UTF_8

  /**
   * Helper method for recursively parsing a list of bytes into a string value.
   * @param targetSize the target number of bytes to parse into a string
   * @param current the current list of bytes that will be converted into a string
   * @param remaining the remaining bytes that have not yet been parsed
   * @return a `ParseResult` which returns a string on success
   */
  @tailrec
  private[binary] def stringParsingHelper(
    targetSize: Int,
    current:    List[Byte],
    remaining:  LazyList[Byte]
  ): DecoderResult[String] =
    if (current.length >= targetSize) (new String(current.toArray, stringCharacterSet), remaining).asRight
    else
      remaining match {
        case head #:: tail => stringParsingHelper(targetSize, current :+ head, tail)
        case _             => DecoderFailure.asLeft
      }

  /**
   * Represents a string with a byte representation of 255 bytes or less.
   * @param value the string representation
   */
  @newtype
  class SmallString(val value: String)

  object SmallString {

    val maxBytes: Int = 255

    def validated(from: String): Either[ValidationFailure, SmallString] = {
      val bytes = from.getBytes(stringCharacterSet)

      Either.cond(bytes.length <= maxBytes, from.coerce, ValidationFailure)
    }

    def unsafe(from: String): SmallString =
      validated(from)
        .getOrElse(
          throw new IllegalArgumentException(
            s"value length is outside the bounds of 0 and $maxBytes"
          )
        )
  }

  /**
   * Represents a string with a byte representation of 2^31^-1 bytes or less.
   * @param value the byte representation of a UTF-8 encoded string
   */
  @newtype
  class IntString(val value: String)

  object IntString {

    val maxBytes: Int = Int.MaxValue

    def validated(from: String): Either[ValidationFailure, IntString] = {
      val bytes = from.getBytes(stringCharacterSet)

      Either.cond(bytes.length <= maxBytes, from.coerce, ValidationFailure)
    }

    def unsafe(from: String): IntString =
      validated(from)
        .getOrElse(
          throw new IllegalArgumentException(
            s"value length is outside the bounds of 0 and $maxBytes"
          )
        )
  }

  trait Implicits
      extends LazyBytesDecoder.Implicits
      with LazyBytesDecoder.ToLazyBytesDecoderOps
      with BooleanCodec.Implicits
      with SmallStringCodec.Implicits
      with Int128Codec.Implicits
      with IntCodec.Implicits
      with IntStringCodec.Implicits
      with LongCodec.Implicits
      with OptionCodec.Implicits
      with ShortCodec.Implicits
      with UIntCodec.Implicits
      with ULongCodec.Implicits
      with UShortCodec.Implicits

  object implicits extends Implicits
}
