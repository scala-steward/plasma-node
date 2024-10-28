package org.plasmalabs.codecs.bytes.tetra

import cats.data.NonEmptySet
import cats.implicits._
import com.google.protobuf.ByteString
import org.plasmalabs.codecs.bytes.scodecs._
import org.plasmalabs.codecs.bytes.typeclasses.Persistable
import org.plasmalabs.consensus.models.BlockId
import org.plasmalabs.crypto.models.SecretKeyKesProduct
import org.plasmalabs.models.{Epoch, ProposalId}
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}
import scodec.Codec

import scala.collection.immutable.SortedSet
import scala.util.Try

trait TetraPersistableCodecs {
  import TetraScodecCodecs._

  implicit def persistableProtobufMessage[T <: GeneratedMessage: GeneratedMessageCompanion]: Persistable[T] =
    new Persistable[T] {
      def persistedBytes(value: T): ByteString = value.toByteString

      def fromPersistedBytes(bytes: ByteString): Either[String, T] =
        Try(implicitly[GeneratedMessageCompanion[T]].parseFrom(bytes.newCodedInput())).toEither
          .leftMap(_.getMessage)
    }

  implicit def persistableSeq[T: Codec]: Persistable[Seq[T]] =
    Persistable.instanceFromCodec(using seqCodec[T])

  implicit def persistableSet[T: Codec]: Persistable[Set[T]] = new Persistable[Set[T]] {
    override def persistedBytes(value: Set[T]): ByteString = persistableSeq[T].persistedBytes(value.toSeq)

    override def fromPersistedBytes(bytes: ByteString): Either[String, Set[T]] =
      persistableSeq[T].fromPersistedBytes(bytes).map(_.toSet)
  }

  implicit val persistableTransactionOutputIndices: Persistable[NonEmptySet[Short]] =
    Persistable.instanceFromCodec(using
      seqCodec[Short].xmap(s => NonEmptySet.fromSetUnsafe(SortedSet.from(s)), _.toList)
    )

  implicit val persistableLong: Persistable[Long] =
    Persistable.instanceFromCodec(using longCodec)

  implicit val persistableInt: Persistable[Int] =
    Persistable.instanceFromCodec(using intCodec)

  implicit val persistableBigInt: Persistable[BigInt] =
    Persistable.instanceFromCodec

  implicit val persistableUnit: Persistable[Unit] =
    new Persistable[Unit] {
      def persistedBytes(value: Unit): ByteString = ByteString.copyFrom(Array[Byte](0))

      def fromPersistedBytes(bytes: ByteString): Either[String, Unit] =
        Either.cond(bytes.size() == 1 && bytes.byteAt(0) == (0: Byte), (), "Invalid Unit")
    }

  implicit val persistableHeightIdTuple: Persistable[(Long, BlockId)] =
    Persistable.instanceFromCodec(tupleCodec(longCodec, blockIdCodec))

  implicit val persistableEpochToProposalId: Persistable[(Epoch, ProposalId)] =
    Persistable.instanceFromCodec(tupleCodec(longCodec, intCodec))

  implicit val persistableByte: Persistable[Byte] =
    Persistable.instanceFromCodec

  implicit val persistableByteArray: Persistable[Array[Byte]] =
    Persistable.instanceFromCodec

  implicit val persistableByteString: Persistable[ByteString] =
    Persistable.instanceFromCodec

  implicit val persistableKesProductSecretKey: Persistable[SecretKeyKesProduct] =
    Persistable.instanceFromCodec

  implicit def persistableFromCodec[T: Codec]: Persistable[T] = Persistable.instanceFromCodec
}

object TetraPersistableCodecs extends TetraPersistableCodecs
