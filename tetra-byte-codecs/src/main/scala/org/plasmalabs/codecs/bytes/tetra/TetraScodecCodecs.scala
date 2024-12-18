package org.plasmalabs.codecs.bytes.tetra

import org.plasmalabs.codecs.bytes.scodecs.*
import org.plasmalabs.codecs.bytes.scodecs.valuetypes.byteStringCodec
import org.plasmalabs.consensus.models.*
import org.plasmalabs.crypto.models as nodeCryptoModels
import org.plasmalabs.models.*
import org.plasmalabs.models.utility.*
import org.plasmalabs.node.models.KnownHost
import scodec.Codec
import scodec.codecs.*

/**
 * Use this object or the package object to access all of the codecs from outside of this package.
 */
object TetraScodecCodecs extends TetraScodecCodecs

trait TetraScodecCodecs {

  // todo: JAA - consider implications of variable vs. fixed length (BigInt vs. Int128)
  // (I think this is never transmitted so probably safe if we used built in BigInt)

  implicit val bigIntCodec: Codec[BigInt] =
    byteArrayCodec.xmapc(t => BigInt(t))(t => t.toByteArray)

  implicit val ratioCodec: Codec[Ratio] =
    (bigIntCodec :: bigIntCodec)
      .xmapc { (numerator, denominator) =>
        Ratio(numerator, denominator)
      } { ratio =>
        (
          ratio.numerator,
          ratio.denominator
        )
      }
      .as[Ratio]

  implicit val unknownFieldSetCodec: Codec[scalapb.UnknownFieldSet] =
    emptyCodec(scalapb.UnknownFieldSet.empty)

  implicit val nodeCryptoKesBinaryTreeCodec: Codec[nodeCryptoModels.KesBinaryTree] = {
    val kesBinaryTreeEmptyCodec: Codec[nodeCryptoModels.KesBinaryTree.Empty] =
      emptyCodec(nodeCryptoModels.KesBinaryTree.Empty())

    val nodeCryptoKesBinaryTreeLeafCodec: Codec[nodeCryptoModels.KesBinaryTree.SigningLeaf] =
      (byteArrayCodecSized(32) :: byteArrayCodecSized(32))
        .as[nodeCryptoModels.KesBinaryTree.SigningLeaf]

    val nodeCryptoKesBinaryTreeNodeCodec: Codec[nodeCryptoModels.KesBinaryTree.MerkleNode] =
      lazily(
        (byteArrayCodecSized(32) :: byteArrayCodecSized(32) :: byteArrayCodecSized(
          32
        ) :: nodeCryptoKesBinaryTreeCodec :: nodeCryptoKesBinaryTreeCodec)
          .as[nodeCryptoModels.KesBinaryTree.MerkleNode]
      )

    discriminated[nodeCryptoModels.KesBinaryTree]
      .by(byteCodec)
      .typecase(nodeCryptoModels.KesBinaryTree.emptyTypePrefix, kesBinaryTreeEmptyCodec)
      .typecase(nodeCryptoModels.KesBinaryTree.leafTypePrefix, nodeCryptoKesBinaryTreeLeafCodec)
      .typecase(nodeCryptoModels.KesBinaryTree.nodeTypePrefix, nodeCryptoKesBinaryTreeNodeCodec)
  }

  implicit val nodeCryptoSignatureKesSumCodec: Codec[nodeCryptoModels.SignatureKesSum] =
    (byteArrayCodecSized(32) :: byteArrayCodecSized(64) :: seqCodec(using byteArrayCodecSized(32)))
      .as[nodeCryptoModels.SignatureKesSum]

  implicit val nodeCryptoSecretKeyKesSumCodec: Codec[nodeCryptoModels.SecretKeyKesSum] =
    (nodeCryptoKesBinaryTreeCodec :: uLongCodec)
      .as[nodeCryptoModels.SecretKeyKesSum]

  implicit val nodeCryptoSecretKeyKesProductCodec: Codec[nodeCryptoModels.SecretKeyKesProduct] =
    (
      nodeCryptoKesBinaryTreeCodec ::
        nodeCryptoKesBinaryTreeCodec ::
        byteArrayCodecSized(32) ::
        nodeCryptoSignatureKesSumCodec ::
        longCodec
    )
      .as[nodeCryptoModels.SecretKeyKesProduct]

  implicit val blockIdCodec: Codec[BlockId] =
    byteStringCodecSized(32).xmap(BlockId(_), _.value)

  implicit val consensusEligibilityCertificateCodec: Codec[EligibilityCertificate] =
    (byteStringCodecSized(80) :: // vrfSig
      byteStringCodecSized(32) :: // vrfVk
      byteStringCodecSized(32) :: // thresholdEvidence
      byteStringCodecSized(32) :: // eta
      emptyCodec(scalapb.UnknownFieldSet.empty))
      .as[EligibilityCertificate]

  implicit val vkKesProductCodec: Codec[VerificationKeyKesProduct] =
    (byteStringCodecSized(32) :: intCodec :: unknownFieldSetCodec)
      .as[VerificationKeyKesProduct]

  implicit val signatureKesSumCodec: Codec[SignatureKesSum] =
    (byteStringCodecSized(32) :: byteStringCodecSized(64) :: seqCodec(using
      byteStringCodecSized(32)
    ) :: unknownFieldSetCodec)
      .as[SignatureKesSum]

  implicit val signatureKesProductCodec: Codec[SignatureKesProduct] =
    (signatureKesSumCodec :: signatureKesSumCodec :: byteStringCodecSized(32) :: unknownFieldSetCodec)
      .as[SignatureKesProduct]

  implicit val operationalCertificateCodec: Codec[OperationalCertificate] =
    (
      vkKesProductCodec ::
        signatureKesProductCodec ::
        byteStringCodecSized(32) ::
        byteStringCodecSized(64) ::
        unknownFieldSetCodec
    )
      .as[OperationalCertificate]

  implicit val partialOperationalCertificateCodec: Codec[UnsignedBlockHeader.PartialOperationalCertificate] =
    (vkKesProductCodec :: signatureKesProductCodec :: byteStringCodecSized(32))
      .as[UnsignedBlockHeader.PartialOperationalCertificate]

  implicit val stakingAddressCodec: Codec[StakingAddress] =
    (byteStringCodecSized(32) :: unknownFieldSetCodec).as[StakingAddress]

  implicit val versionCodec: Codec[ProtocolVersion] =
    (intCodec :: intCodec :: intCodec :: unknownFieldSetCodec).as[ProtocolVersion]

  implicit val consensusBlockHeaderCodec: Codec[BlockHeader] = (
    emptyCodec[Option[BlockId]](None) :: // headerId
      blockIdCodec :: // parentHeaderId
      longCodec :: // parentSlot
      byteStringCodecSized(32) :: // txRoot
      byteStringCodecSized(256) :: // bloomFilter
      longCodec :: // timestamp
      longCodec :: // height
      longCodec :: // slot
      consensusEligibilityCertificateCodec ::
      operationalCertificateCodec ::
      byteStringCodec :: // metadata
      stakingAddressCodec :: // address
      versionCodec ::
      unknownFieldSetCodec
  ).as[BlockHeader]

  implicit val unsignedBlockHeaderCodec: Codec[UnsignedBlockHeader] = (
    blockIdCodec :: // parentHeaderId
      longCodec :: // parentSlot
      byteStringCodecSized(32) :: // txRoot
      byteStringCodecSized(256) :: // bloomFilter
      longCodec :: // timestamp
      longCodec :: // height
      longCodec :: // slot
      consensusEligibilityCertificateCodec ::
      partialOperationalCertificateCodec ::
      byteStringCodec :: // metadata
      stakingAddressCodec :: // address
      versionCodec
  ).as[UnsignedBlockHeader]

  implicit val slotIdCodec: Codec[SlotId] = (
    longCodec :: // slot
      blockIdCodec :: // blockId
      unknownFieldSetCodec
  ).as[SlotId]

  implicit val slotDataCodec: Codec[SlotData] = (
    slotIdCodec :: // slotId
      slotIdCodec :: // parentSlotId
      byteStringCodecSized(64) :: // rho
      byteStringCodecSized(32) :: // eta
      longCodec :: // height
      unknownFieldSetCodec // unknownFields
  ).as[SlotData]

  implicit val knownHostCodec: Codec[KnownHost] = (
    byteStringCodec ::
      utf8_32 ::
      intCodec ::
      unknownFieldSetCodec
  ).as[KnownHost]

  implicit def pairCodec[A: Codec, B: Codec]: Codec[(A, B)] =
    (
      implicitly[Codec[A]] ::
        implicitly[Codec[B]]
    ).as[(A, B)]
}
