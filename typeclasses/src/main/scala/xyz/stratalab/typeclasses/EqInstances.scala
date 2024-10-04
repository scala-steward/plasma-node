package xyz.stratalab.typeclasses

import cats.Eq
import cats.implicits._
import com.google.protobuf.ByteString
import grpc.health.v1.ServingStatus
import xyz.stratalab.consensus.models._
import xyz.stratalab.crypto.generation.mnemonic.Entropy
import xyz.stratalab.models._
import xyz.stratalab.models.utility.Sized
import xyz.stratalab.sdk.models.TransactionId

trait EqInstances {

  implicit def arrayEq[T: Eq]: Eq[Array[T]] =
    (a, b) => a.length == b.length && a.zip(b).forall { case (a1, b1) => a1 === b1 }

  implicit val bytesStringEq: Eq[ByteString] =
    Eq.fromUniversalEquals

  implicit def sizedMaxEq[T: Eq, L]: Eq[Sized.Max[T, L]] =
    (a, b) => a.data === b.data

  implicit def sizedStrictEq[T: Eq, L]: Eq[Sized.Strict[T, L]] =
    (a, b) => a.data === b.data

  implicit val entropyEq: Eq[Entropy] =
    (a, b) => a.value === b.value

  implicit val rhoEq: Eq[Rho] =
    (a, b) => a.sizedBytes === b.sizedBytes

  implicit val blockIdEq: Eq[BlockId] =
    (a, b) => a.value === b.value

  implicit val transactionIdEq: Eq[TransactionId] =
    (a, b) => a.value === b.value

  implicit val eqSlotData: Eq[SlotData] =
    Eq.fromUniversalEquals

  implicit val eqServiceStatus: Eq[ServiceStatus] =
    Eq.fromUniversalEquals

  implicit val eqServingStatus: Eq[ServingStatus] =
    Eq.fromUniversalEquals
}
