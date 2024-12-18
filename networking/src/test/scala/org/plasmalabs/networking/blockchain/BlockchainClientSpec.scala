package org.plasmalabs.networking.blockchain

import cats.effect.IO
import cats.implicits.*
import com.google.protobuf.ByteString
import fs2.*
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.plasmalabs.consensus.models.{BlockHeader, BlockId, SlotData}
import org.plasmalabs.crypto.hash.Blake2b256
import org.plasmalabs.networking.p2p.ConnectedPeer
import org.plasmalabs.node.models.*
import org.plasmalabs.sdk.models.TransactionId
import org.plasmalabs.sdk.models.transaction.IoTransaction
import org.scalacheck.Gen
import org.scalacheck.effect.PropF
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.nio.charset.StandardCharsets

class BlockchainClientSpec extends CatsEffectSuite with ScalaCheckEffectSuite {

  implicit private val logger: Logger[F] = Slf4jLogger.getLogger[F]

  type F[A] = IO[A]

  test("trace a common ancestor") {
    PropF.forAllF(Gen.posNum[Long]) { headHeight =>
      PropF.forAllF(Gen.chooseNum[Long](1, headHeight)) { ancestorHeight =>
        val client = new BlockchainPeerClient[F] {
          def remotePeer: ConnectedPeer = ???

          def remotePeerAsServer: F[Option[KnownHost]] = ???

          def remotePeerAdoptions: F[Stream[F, BlockId]] = ???

          def remoteTransactionNotifications: F[Stream[F, TransactionId]] = ???

          def getRemoteSlotData(id: BlockId): F[Option[SlotData]] = ???

          def getRemoteHeader(id: BlockId): F[Option[BlockHeader]] = ???

          def getRemoteBody(id: BlockId): F[Option[BlockBody]] = ???

          def getRemoteTransaction(id: TransactionId): F[Option[IoTransaction]] = ???

          def getRemoteBlockIdAtHeight(
            height: Long
          ): F[Option[BlockId]] =
            (height.toString + (if (height > ancestorHeight) "remote" else "")).typedId.some
              .pure[F]

          override def getRemoteBlockIdAtDepth(depth: Long): F[Option[BlockId]] = ???

          override def getRemoteKnownHosts(request: CurrentKnownHostsReq): F[Option[CurrentKnownHostsRes]] = ???

          override def getPongMessage(request: PingMessage): F[Option[PongMessage]] = ???

          override def notifyAboutThisNetworkLevel(networkLevel: Boolean): F[Unit] = ???

          override def closeConnection(): F[Unit] = ???

          override def getRemoteSlotDataWithParents(from: BlockId, to: BlockId): F[Option[List[SlotData]]] = ???
        }

        val blockHeights =
          (height: Long) =>
            (height.toString + (if (height > ancestorHeight) "local" else "")).typedId
              .pure[F]

        client.findCommonAncestor(blockHeights, () => headHeight.pure[F]).assertEquals(ancestorHeight.toString.typedId)
      }
    }
  }

  implicit private class StringToBlockId(string: String) {
    def typedId: BlockId = BlockId(ByteString.copyFrom(new Blake2b256().hash(string.getBytes(StandardCharsets.UTF_8))))
  }

}
