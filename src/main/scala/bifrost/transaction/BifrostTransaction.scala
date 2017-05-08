package bifrost.transaction

import com.google.common.primitives.{Bytes, Ints, Longs}
import bifrost.transaction.PolyTransfer.Nonce
import bifrost.contract._
import bifrost.scorexMod.GenericBoxTransaction
import bifrost.transaction.box.proposition.{MofNProposition, MofNPropositionSerializer}
import bifrost.transaction.box.{BifrostBox, ContractBox, PolyBox, ProfileBox}
import bifrost.transaction.proof.MultiSignature25519
import bifrost.wallet.BWallet
import io.circe.Json
import io.circe.syntax._
import scorex.core.crypto.hash.FastCryptographicHash
import scorex.core.transaction.account.PublicKeyNoncedBox
import scorex.core.transaction.box.BoxUnlocker
import scorex.core.transaction.box.proposition.{ProofOfKnowledgeProposition, PublicKey25519Proposition}
import scorex.core.transaction.proof.{Proof, Signature25519}
import scorex.core.transaction.state.{PrivateKey25519, PrivateKey25519Companion}
import scorex.crypto.encode.Base58

import scala.util.Try

sealed trait BifrostTransaction extends GenericBoxTransaction[ProofOfKnowledgeProposition[PrivateKey25519], Any, BifrostBox] {
  val boxIdsToOpen: IndexedSeq[Array[Byte]]
}

sealed abstract class ContractTransaction extends BifrostTransaction

// 3 signatures FOR A SPECIFIC MESSAGE <agreement: Agreement>
// ContractCreation(agreement ++ nonce, IndexSeq(pk1, pk2, pk3), IndexSeq(sign1(agreement ++ nonce), sign2(agreement ++ nonce), sign3(agreement ++ nonce)) )
// validity check: decrypt[pk1] sign1(agreement) === agreement
// agreement specifies "executeBy" date
case class ContractCreation(agreement: Agreement,
                            parties: IndexedSeq[PublicKey25519Proposition],
                            signatures: IndexedSeq[Signature25519],
                            fee: Long,
                            timestamp: Long)
  extends ContractTransaction {

  override type M = ContractCreation

  lazy val proposition = MofNProposition(1, parties.map(_.pubKeyBytes).toSet)

  // no boxes required for now -- will require reputation
  lazy val boxIdsToOpen: IndexedSeq[Array[Byte]] = IndexedSeq[Array[Byte]]()

  override lazy val unlockers: Traversable[BoxUnlocker[MofNProposition]] = boxIdsToOpen.zip(signatures).map {
    case (boxId, signature) =>
      new BoxUnlocker[MofNProposition] {
        override val closedBoxId: Array[Byte] = boxId
        override val boxKey: Proof[MofNProposition] = MultiSignature25519(Set(signature))
      }
  }

  lazy val hashNoNonces = FastCryptographicHash(
    AgreementCompanion.toBytes(agreement) ++
      parties.foldLeft(Array[Byte]())((a, b) => a ++ b.pubKeyBytes) ++
      unlockers.map(_.closedBoxId).foldLeft(Array[Byte]())(_ ++ _) ++
      Longs.toByteArray(timestamp) ++
      Longs.toByteArray(fee)
  )


  override lazy val newBoxes: Traversable[BifrostBox] = {
    // TODO check if this nonce is secure
    val digest = FastCryptographicHash(MofNPropositionSerializer.toBytes(proposition) ++ hashNoNonces)
    val nonce = ContractCreation.nonceFromDigest(digest)
    val agreementString = new String(messageToSign)
    IndexedSeq(ContractBox(proposition, nonce, agreementString))
  }

  override lazy val json: Json = Map(
    "agreement" -> agreement.json,
    "parties" -> parties.map( p => Base58.encode(p.pubKeyBytes).asJson ).asJson,
    "signatures" -> signatures.map(s => Base58.encode(s.signature).asJson).asJson,
    "fee" -> fee.asJson,
    "timestamp" -> timestamp.asJson
  ).asJson

  override lazy val serializer = ContractCreationCompanion

  override lazy val messageToSign: Array[Byte] = Bytes.concat(
    Longs.toByteArray(timestamp),
    AgreementCompanion.toBytes(agreement),
    parties.foldLeft(Array[Byte]())((a, b) => a ++ b.pubKeyBytes)
  )

  override def toString: String = s"ContractCreation(${json.noSpaces})"

}

object ContractCreation {
  type Value = Long
  type Nonce = Long

  def nonceFromDigest(digest: Array[Byte]): Nonce = Longs.fromByteArray(digest.take(8))

  def validate(tx: ContractCreation): Try[Unit] = Try {

    require(Agreement.validate(tx.agreement).isSuccess)

    require(tx.parties.size == tx.signatures.size && tx.parties.size == 3)
    require(tx.fee >= 0)
    require(tx.timestamp >= 0)
    require(tx.signatures.zip(tx.parties) forall { case (signature, proposition) =>
      signature.isValid(proposition, tx.messageToSign)
    })
  }

}


trait TransferTransaction extends BifrostTransaction

case class PolyTransfer(from: IndexedSeq[(PublicKey25519Proposition, Nonce)],
                        to: IndexedSeq[(PublicKey25519Proposition, Long)],
                        signatures: IndexedSeq[Signature25519],
                        override val fee: Long,
                        override val timestamp: Long)
  extends TransferTransaction {

  override type M = PolyTransfer

  override lazy val serializer = PolyTransferCompanion

  override def toString: String = s"TransferTransaction(${json.noSpaces})"

  lazy val boxIdsToOpen: IndexedSeq[Array[Byte]] = from.map { case (prop, nonce) =>
    PublicKeyNoncedBox.idFromBox(prop, nonce)
  }

  override lazy val unlockers: Traversable[BoxUnlocker[PublicKey25519Proposition]] = boxIdsToOpen.zip(signatures).map {
    case (boxId, signature) =>
      new BoxUnlocker[PublicKey25519Proposition] {
        override val closedBoxId: Array[Byte] = boxId
        override val boxKey: Signature25519 = signature
      }
  }

  lazy val hashNoNonces = FastCryptographicHash(
    to.map(_._1.pubKeyBytes).reduce(_ ++ _) ++
      unlockers.map(_.closedBoxId).reduce(_ ++ _) ++
      Longs.toByteArray(timestamp) ++
      Longs.toByteArray(fee)
  )

  override lazy val newBoxes: Traversable[BifrostBox] = to.zipWithIndex.map {
    case ((prop, value), idx) =>
      val nonce = PolyTransfer.nonceFromDigest(FastCryptographicHash(prop.pubKeyBytes ++ hashNoNonces ++ Ints.toByteArray(idx)))
      PolyBox(prop, nonce, value)
  }

  override lazy val json: Json = Map(
    "id" -> Base58.encode(id).asJson,
    "newBoxes" -> newBoxes.map(b => Base58.encode(b.id).asJson).asJson,
    "boxesToRemove" -> boxIdsToOpen.map(id => Base58.encode(id).asJson).asJson,
    "from" -> from.map { s =>
      Map(
        "proposition" -> Base58.encode(s._1.pubKeyBytes).asJson,
        "nonce" -> s._2.asJson
      ).asJson
    }.asJson,
    "to" -> to.map { s =>
      Map(
        "proposition" -> Base58.encode(s._1.pubKeyBytes).asJson,
        "value" -> s._2.asJson
      ).asJson
    }.asJson,
    "signatures" -> signatures.map(s => Base58.encode(s.signature).asJson).asJson,
    "fee" -> fee.asJson,
    "timestamp" -> timestamp.asJson
  ).asJson
}

object PolyTransfer {
  type Value = Long
  type Nonce = Long

  def nonceFromDigest(digest: Array[Byte]): Nonce = Longs.fromByteArray(digest.take(8))

  def apply(from: IndexedSeq[(PrivateKey25519, Nonce)],
            to: IndexedSeq[(PublicKey25519Proposition, Value)],
            fee: Long,
            timestamp: Long): PolyTransfer = {
    val fromPub = from.map { case (pr, n) => pr.publicImage -> n }
    val fakeSigs = from.map(_ => Signature25519(Array()))

    val undersigned = PolyTransfer(fromPub, to, fakeSigs, fee, timestamp)

    val msg = undersigned.messageToSign
    val sigs = from.map { case (priv, _) => PrivateKey25519Companion.sign(priv, msg) }

    new PolyTransfer(fromPub, to, sigs, fee, timestamp)
  }

  //TODO seq of recipients and amounts
  def create(w: BWallet, recipient: PublicKey25519Proposition, amount: Long, fee: Long): Try[PolyTransfer] = Try {

    val from: IndexedSeq[(PrivateKey25519, Long, Long)] = w.boxes().flatMap { b => b.box match {
        case scb: PolyBox => w.secretByPublicImage(scb.proposition).map (s => (s, scb.nonce, scb.value) )
        case _ => None
      }
    }.toIndexedSeq

    val canSend = from.map(_._3).sum
    val updatedBalance: (PublicKey25519Proposition, Long) = (w.publicKeys.find {
      case _: PublicKey25519Proposition => true
      case _ => false
    }.get.asInstanceOf[PublicKey25519Proposition], canSend - amount - fee)

    val to: IndexedSeq[(PublicKey25519Proposition, Long)] = IndexedSeq(updatedBalance, (recipient, amount))

    require(from.map(_._3).sum - to.map(_._2).sum == fee)

    val timestamp = System.currentTimeMillis()
    PolyTransfer(from.map(t => t._1 -> t._2), to, fee, timestamp)
  }

  def validate(tx: PolyTransfer): Try[Unit] = Try {
      require(tx.from.size == tx.signatures.size)
      require(tx.to.forall(_._2 >= 0))
      require(tx.fee >= 0)
      require(tx.timestamp >= 0)
      require(tx.from.zip(tx.signatures).forall { case ((prop, _), proof) =>
        proof.isValid(prop, tx.messageToSign)
      })
  }

}

case class ProfileTransaction(from: PublicKey25519Proposition,
                       signatures: IndexedSeq[Signature25519],
                       keyValues: Map[String, String],
                       override val fee: Long,
                       override val timestamp: Long)
  extends BifrostTransaction{

  override type M = ProfileTransaction

  override lazy val serializer = ProfileTransactionCompanion

  lazy val boxIdsToOpen: IndexedSeq[Array[Byte]] = keyValues.keys.toIndexedSeq.map(
    key => ProfileBox.idFromBox(from, key)
  )

  override lazy val unlockers: Traversable[BoxUnlocker[PublicKey25519Proposition]] = boxIdsToOpen.zip(signatures).map {
    case (boxId, signature) =>
      new BoxUnlocker[PublicKey25519Proposition] {
        override val closedBoxId: Array[Byte] = boxId
        override val boxKey: Signature25519 = signature
      }
  }

  override lazy val newBoxes: Traversable[BifrostBox] = keyValues.map {
    case (key, value) => ProfileBox(from, 0L, value, key)
  }

  override lazy val messageToSign: Array[Byte] = Bytes.concat(
    Longs.toByteArray(timestamp),
    keyValues.asJson.toString().getBytes()
  )

  override lazy val json: Json = Map(
    "id" -> Base58.encode(id).asJson,
    "newBoxes" -> newBoxes.map(b => Base58.encode(b.id).asJson).asJson,
    "boxesToRemove" -> boxIdsToOpen.map(id => Base58.encode(id).asJson).asJson,
    "from" -> Base58.encode(from.pubKeyBytes).asJson,
    "signatures" -> signatures.map(s => Base58.encode(s.signature).asJson).asJson,
    "keyValues" -> keyValues.asJson,
    "fee" -> fee.asJson,
    "timestamp" -> timestamp.asJson
  ).asJson
}