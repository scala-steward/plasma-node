package co.topl.attestation.keyManagement

import cats.implicits._
import co.topl.attestation.{PublicKeyPropositionEd25519, SignatureEd25519}
import co.topl.crypto.implicits._
import co.topl.crypto.signatures.eddsa.Ed25519
import co.topl.crypto.{PrivateKey, PublicKey}
import co.topl.utils.serialization.{BifrostSerializer, Reader, Writer}

case class PrivateKeyEd25519(private val privateKey: PrivateKey, private val publicKey: PublicKey) extends Secret {

  private val privateKeyLength = privateKey.value.length
  private val publicKeyLength = publicKey.value.length

  private val ed25519 = new Ed25519

  require(privateKeyLength == ed25519.KeyLength, s"$privateKeyLength == ${ed25519.KeyLength}")
  require(publicKeyLength == ed25519.KeyLength, s"$publicKeyLength == ${ed25519.KeyLength}")

  override type S = PrivateKeyEd25519
  override type PK = PublicKeyPropositionEd25519
  override type PR = SignatureEd25519
  override type KF = KeyfileEd25519

  override lazy val serializer: BifrostSerializer[PrivateKeyEd25519] = PrivateKeyEd25519

  override lazy val publicImage: PublicKeyPropositionEd25519 = PublicKeyPropositionEd25519(publicKey)

  override def sign(message: Array[Byte]): SignatureEd25519 = SignatureEd25519(
    ed25519.sign(privateKey, message)
  )

  override def equals(obj: Any): Boolean = obj match {
    case sk: PrivateKeyEd25519 => sk.privateKey === privateKey
    case _                     => false
  }
}

object PrivateKeyEd25519 extends BifrostSerializer[PrivateKeyEd25519] {

  private val ed25519KeyLength = new Ed25519().KeyLength

  implicit val secretGenerator: SecretGenerator[PrivateKeyEd25519] = {
    SecretGenerator.instance[PrivateKeyEd25519] { seed: Array[Byte] =>
      val ed25519 = new Ed25519
      val (sk, pk) = ed25519.createKeyPair(seed)
      val secret: PrivateKeyEd25519 = PrivateKeyEd25519(sk, pk)
      secret -> secret.publicImage
    }
  }

  override def serialize(obj: PrivateKeyEd25519, w: Writer): Unit = {
    /* privKeyBytes: Array[Byte] */
    w.putBytes(obj.privateKey.value)

    /* publicKeyBytes: Array[Byte] */
    w.putBytes(obj.publicKey.value)
  }

  override def parse(r: Reader): PrivateKeyEd25519 =
    PrivateKeyEd25519(PrivateKey(r.getBytes(ed25519KeyLength)), PublicKey(r.getBytes(ed25519KeyLength)))

}
