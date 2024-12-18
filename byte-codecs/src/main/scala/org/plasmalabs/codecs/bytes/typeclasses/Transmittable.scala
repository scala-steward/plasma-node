package org.plasmalabs.codecs.bytes.typeclasses

import cats.implicits.*
import com.google.protobuf.ByteString
import scodec.bits.BitVector
import scodec.{Attempt, Codec}

import scala.language.implicitConversions

/**
 * Typeclass for encoding a value into a byte representation which should be communicated over the network to other
 * blockchain nodes.
 *
 * IMPORTANT:
 * The typeclass instance's encoding scheme should never change so as to not break compatibility between nodes.
 *
 * See `Persistable` or `BinaryShow` for alternative use cases of generating binary data.
 *
 * @tparam T the type this type-class is implemented for
 */
trait Transmittable[T] {

  /**
   * Encodes a value into its transmittable bytes representation to be sent to another blockchain node.
   * @param value the value to encode into transmittable data
   * @return the data to transmit to another node
   */
  def transmittableBytes(value: T): ByteString

  /**
   * Attempts to decode a value from its transmitted bytes representation into its expected data type.
   *
   * The byte representation should have been transmitted from another node which encoded the value with the same
   * scheme.
   *
   * @param bytes the transmitted bytes to decode into a value `T`
   * @return if successful, a value of type `T` represented by the transmitted bytes, otherwise a failure message
   */
  def fromTransmittableBytes(bytes: ByteString): Either[String, T]
}

object Transmittable {

  def apply[A](implicit instance: Transmittable[A]): Transmittable[A] = instance

  trait Ops[A] {
    def typeClassInstance: Transmittable[A]
    def self: A
  }

  trait ToTransmittableOps {

    implicit def toTransmittableOps[A](target: A)(implicit tc: Transmittable[A]): Ops[A] = new Ops[A] {
      val self: A = target
      val typeClassInstance: Transmittable[A] = tc
    }
  }

  /**
   * Generates an instance of the `Transmittable` typeclass from an instance of the Scodec `Codec` typeclass.
   * @tparam T the type of value that can be transmitted with an existing `Codec` instance
   * @return an instance of the `Transmittable` typeclass for value `T`
   */
  def instanceFromCodec[T: Codec]: Transmittable[T] = new Transmittable[T] {

    override def transmittableBytes(value: T): ByteString =
      Codec[T].encode(value) match {
        case Attempt.Successful(value) => ByteString.copyFrom(value.toByteBuffer)
        case Attempt.Failure(cause)    => throw new IllegalArgumentException(cause.messageWithContext)
      }

    override def fromTransmittableBytes(bytes: ByteString): Either[String, T] =
      Codec[T].decodeValue(BitVector(bytes.asReadOnlyByteBuffer())).toEither.leftMap(_.message)
  }

  /**
   * Extension operations for working with transmitted data in the form of a protobuf ByteString
   * @param value the transmitted byte string value
   */
  class ByteStringTransmittableOps(private val byteString: ByteString) extends AnyVal {

    /**
     * Attempts to decode byte data transmitted from another blockchain node into a value of type `T`.
     * @tparam T the type of value to try and decode the byte data to.
     * @return if successful, the value which the transmitted byte data represents, otherwise a failure message
     */
    def decodeTransmitted[T: Transmittable]: Either[String, T] =
      Transmittable[T].fromTransmittableBytes(byteString)
  }

  trait ToExtensionOps {

    implicit def transmittableFromByteString(value: ByteString): ByteStringTransmittableOps =
      new ByteStringTransmittableOps(value)
  }
}
