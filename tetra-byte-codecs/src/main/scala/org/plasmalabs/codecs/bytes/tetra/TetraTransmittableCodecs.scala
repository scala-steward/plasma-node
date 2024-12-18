package org.plasmalabs.codecs.bytes.tetra

import cats.implicits.*
import com.google.protobuf.ByteString
import org.plasmalabs.codecs.bytes.typeclasses.Transmittable
import org.plasmalabs.consensus.models.BlockId
import org.plasmalabs.models.utility.Ratio
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}
import scodec.Codec
import scodec.codecs.*

import scala.util.Try

trait TetraTransmittableCodecs {

  import TetraScodecCodecs._
  import org.plasmalabs.codecs.bytes.scodecs._

  implicit def transmittableProtobufMessage[T <: GeneratedMessage: GeneratedMessageCompanion]: Transmittable[T] =
    new Transmittable[T] {
      def transmittableBytes(value: T): ByteString = value.toByteString

      def fromTransmittableBytes(bytes: ByteString): Either[String, T] =
        Try(implicitly[GeneratedMessageCompanion[T]].parseFrom(bytes.toByteArray)).toEither
          .leftMap(_.getMessage)
    }

  implicit val ratioTransmittable: Transmittable[Ratio] = Transmittable.instanceFromCodec

  implicit val unitTransmittable: Transmittable[Unit] = new Transmittable[Unit] {
    override def transmittableBytes(value:     Unit): ByteString = ByteString.EMPTY
    override def fromTransmittableBytes(bytes: ByteString): Either[String, Unit] = Right(())
  }

  implicit val booleanTransmittable: Transmittable[Boolean] = Transmittable.instanceFromCodec(using boolCodec)

  implicit val intTransmittable: Transmittable[Int] = Transmittable.instanceFromCodec(using intCodec)

  implicit val longTransmittable: Transmittable[Long] = Transmittable.instanceFromCodec(using longCodec)

  implicit val longBlockIdOptTransmittable: Transmittable[(Long, Option[BlockId])] =
    Transmittable.instanceFromCodec(using
      (longCodec :: optionCodec[BlockId])
        .as[(Long, Option[BlockId])]
    )

  implicit def listTransmittable[A: Codec]: Transmittable[List[A]] =
    Transmittable.instanceFromCodec(using list[A](implicitly[Codec[A]]))

  implicit def pairTransmittable[A: Codec, B: Codec]: Transmittable[(A, B)] =
    Transmittable.instanceFromCodec(using pairCodec[A, B])

  implicit def optionalTransmittable[T: Transmittable]: Transmittable[Option[T]] =
    new Transmittable[Option[T]] {

      def transmittableBytes(value: Option[T]): ByteString =
        value
          .map(Transmittable[T].transmittableBytes)
          .fold(ZeroBS)(OneBS.concat)

      def fromTransmittableBytes(bytes: ByteString): Either[String, Option[T]] =
        Either
          .cond(bytes.size() > 0, bytes, "Empty Bytes")
          .flatMap(bytes =>
            bytes.byteAt(0) match {
              case 0 => none[T].asRight[String]
              case 1 => Transmittable[T].fromTransmittableBytes(bytes.substring(1)).map(_.some)
              case _ => "Invalid Optional".asLeft[Option[T]]
            }
          )
    }

  val ZeroBS: ByteString = ByteString.copyFrom(Array[Byte](0))
  val OneBS: ByteString = ByteString.copyFrom(Array[Byte](1))

  val RequestMarker: ByteString = ZeroBS
  val ResponseMarker: ByteString = OneBS
}

object TetraTransmittableCodecs extends TetraTransmittableCodecs
