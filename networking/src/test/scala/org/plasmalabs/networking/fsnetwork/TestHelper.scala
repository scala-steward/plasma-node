package org.plasmalabs.networking.fsnetwork

import cats.data.NonEmptyChain
import cats.implicits.*
import com.google.protobuf.ByteString
import org.plasmalabs.codecs.bytes.tetra.instances.*
import org.plasmalabs.consensus.models.{BlockHeader, BlockId, SlotData}
import org.plasmalabs.models.ModelGenerators.GenHelper
import org.plasmalabs.models.generators.consensus.ModelGenerators
import org.plasmalabs.models.generators.consensus.ModelGenerators.*
import org.plasmalabs.models.p2p.*
import org.plasmalabs.node.models.{BlockBody, KnownHost}
import org.plasmalabs.sdk.generators.TransactionGenerator
import org.plasmalabs.sdk.models.transaction.IoTransaction
import org.plasmalabs.sdk.syntax.*
import org.plasmalabs.typeclasses.implicits.*
import org.scalacheck.{Arbitrary, Gen}
import org.scalamock.function.FunctionAdapter1
import org.scalamock.handlers.{CallHandler1, CallHandler2, CallHandler3}

import scala.annotation.tailrec

object TestHelper extends TransactionGenerator {

  implicit class CallHandler1Ops[T1, R](ch: CallHandler1[T1, R]) {
    def rep(count: Int): CallHandler1[T1, R] = ch.repeated(count to count)
  }

  implicit class CallHandler2Ops[T1, T2, R](ch: CallHandler2[T1, T2, R]) {
    def rep(count: Int): CallHandler2[T1, T2, R] = ch.repeated(count to count)
  }

  implicit class CallHandler3Ops[T1, T2, T3, R](ch: CallHandler3[T1, T2, T3, R]) {
    def rep(count: Int): CallHandler3[T1, T2, T3, R] = ch.repeated(count to count)
  }

  @tailrec
  private def addHeaderToChain(
    headers: NonEmptyChain[BlockHeader],
    gen:     Gen[BlockHeader],
    count:   Long
  ): NonEmptyChain[BlockHeader] =
    count match {
      case 0 => headers
      case _ =>
        val parentId = headers.last.id
        addHeaderToChain(headers.append(gen.sample.get.copy(parentHeaderId = parentId)), gen, count - 1)
    }

  val arbitraryIpString: Arbitrary[String] = Arbitrary(
    for {
      first  <- Arbitrary.arbitrary[Byte]
      second <- Arbitrary.arbitrary[Byte]
      third  <- Arbitrary.arbitrary[Byte]
      fourth <- Arbitrary.arbitrary[Byte]
    } yield s"${first & 0xff}.${second & 0xff}.${third & 0xff}.${fourth & 0xff}" // & 0xFF -- to unsigned byte
  )

  implicit val arbitraryKnownHost: Arbitrary[KnownHost] = Arbitrary(
    for {
      idBytes <- Gen.listOfN(hostIdBytesLen, Arbitrary.arbitrary[Byte])
      host    <- arbitraryIpString.arbitrary
      port    <- Gen.long
    } yield KnownHost(ByteString.copyFrom(idBytes.toArray), host.take(12), port.toInt)
  )

  implicit val arbitraryRemoteAddress: Arbitrary[RemoteAddress] = Arbitrary(
    for {
      host <- arbitraryIpString.arbitrary
      port <- Gen.long
    } yield RemoteAddress(host, port.toInt)
  )

  implicit val arbitraryRemotePeer: Arbitrary[RemotePeer] = Arbitrary(
    for {
      idBytes <- Gen.listOfN(hostIdBytesLen, Arbitrary.arbitrary[Byte])
      address <- arbitraryRemoteAddress.arbitrary
    } yield (RemotePeer(HostId(ByteString.copyFrom(idBytes.toArray)), address))
  )

  val arbitraryHost: Arbitrary[HostId] = Arbitrary(
    for {
      bytes <- Gen.listOfN(hostIdBytesLen, Arbitrary.arbitrary[Byte])
    } yield (HostId(ByteString.copyFrom(bytes.toArray)))
  )

  val arbitraryHostBlockId: Arbitrary[(HostId, BlockId)] = Arbitrary(
    for {
      host    <- arbitraryHost.arbitrary
      blockId <- arbitraryBlockId.arbitrary
    } yield (host, blockId)
  )

  def arbitraryLinkedBlockHeaderChain(sizeGen: Gen[Long]): Arbitrary[NonEmptyChain[BlockHeader]] =
    Arbitrary(
      for {
        size <- sizeGen
        headerGen = ModelGenerators.arbitraryHeader.arbitrary.map(_.embedId)
        root <- headerGen
      } yield addHeaderToChain(NonEmptyChain.one(root), headerGen, size)
    )

  val maxTxsCount = 5

  implicit val arbitraryTxsAndBlock: Arbitrary[(Seq[IoTransaction], BlockBody)] =
    Arbitrary(
      for {
        txs <- Gen.listOfN(maxTxsCount, arbitraryIoTransaction.arbitrary.map(_.embedId))
        // TODO: Reward
      } yield (txs, BlockBody(txs.map(tx => tx.id)))
    )

  implicit val arbitraryTxAndBlock: Arbitrary[(IoTransaction, BlockBody)] =
    Arbitrary(
      for {
        tx <- arbitraryIoTransaction.arbitrary.map(_.embedId)
        // TODO: Reward
      } yield (tx, BlockBody(Seq(tx.id)))
    )

  def headerToSlotData(header: BlockHeader): SlotData = {
    val sampleSlotData = ModelGenerators.arbitrarySlotData.arbitrary.first
    val slotId = sampleSlotData.slotId.copy(blockId = header.id)
    val parentSlotId = sampleSlotData.parentSlotId.copy(blockId = header.parentHeaderId)
    sampleSlotData.copy(slotId = slotId, parentSlotId = parentSlotId)
  }

  def arbitraryLinkedSlotDataHeaderBlockNoTx(
    sizeGen: Gen[Long]
  ): Arbitrary[NonEmptyChain[(BlockId, SlotData, BlockHeader, BlockBody)]] =
    Arbitrary(
      for {
        size    <- sizeGen
        headers <- arbitraryLinkedBlockHeaderChain(Gen.oneOf(List[Long](size))).arbitrary
      } yield NonEmptyChain
        .fromSeq(headers.foldLeft(List.empty[(BlockId, SlotData, BlockHeader, BlockBody)]) { case (blocks, header) =>
          val body = org.plasmalabs.models.generators.node.ModelGenerators.arbitraryNodeBody.arbitrary.first
          val headerWithTxRoot = header.copy(txRoot = body.merkleTreeRootHash.data)
          if (blocks.isEmpty) {
            List((headerWithTxRoot.id, headerToSlotData(headerWithTxRoot), headerWithTxRoot, body))
          } else {
            val headerWithParent = headerWithTxRoot.copy(parentHeaderId = blocks.last._2.slotId.blockId).embedId
            blocks.appended((headerWithParent.id, headerToSlotData(headerWithParent), headerWithParent, body))
          }
        })
        .get
    )

  def compareDownloadedHeaderWithoutDownloadTimeMatcher(
    rawExpectedMessage: RequestsProxy.Message
  ): FunctionAdapter1[RequestsProxy.Message, Boolean] = {
    val matchingFunction: RequestsProxy.Message => Boolean =
      (rawActualMessage: RequestsProxy.Message) =>
        (rawExpectedMessage, rawActualMessage) match {
          case (
                expectedMessage: RequestsProxy.Message.DownloadHeadersResponse,
                actualMessage: RequestsProxy.Message.DownloadHeadersResponse
              ) =>
            val newResp =
              actualMessage.response.map { case (header, res) =>
                (header, res.map(b => b.copy(downloadTimeMs = 0)))
              }
            expectedMessage == actualMessage.copy(response = newResp)
          case (_, _) => throw new IllegalStateException("Unexpected case")
        }
    new FunctionAdapter1[RequestsProxy.Message, Boolean](matchingFunction)
  }

  def compareDownloadedBodiesWithoutDownloadTimeMatcher(
    rawExpectedMessage: RequestsProxy.Message
  ): FunctionAdapter1[RequestsProxy.Message, Boolean] = {
    val matchingFunction: RequestsProxy.Message => Boolean =
      (rawActualMessage: RequestsProxy.Message) =>
        (rawExpectedMessage, rawActualMessage) match {
          case (
                expectedMessage: RequestsProxy.Message.DownloadBodiesResponse,
                actualMessage: RequestsProxy.Message.DownloadBodiesResponse
              ) =>
            val newResp =
              actualMessage.response.map { case (header, res) =>
                (header, res.map(b => b.copy(downloadTimeMs = 0, downloadTimeTxMs = Seq.empty)))
              }
            expectedMessage == actualMessage.copy(response = newResp)
          case (_, _) => throw new IllegalStateException("Unexpected case")
        }
    new FunctionAdapter1[RequestsProxy.Message, Boolean](matchingFunction)
  }
}
