package org.plasmalabs.networking.blockchain

import cats.effect.IO
import cats.effect.std.{Mutex, Queue}
import cats.implicits.*
import fs2.Stream
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.plasmalabs.codecs.bytes.tetra.instances.*
import org.plasmalabs.codecs.bytes.typeclasses.Transmittable
import org.plasmalabs.consensus.models.{BlockHeader, BlockId}
import org.plasmalabs.models.Bytes
import org.plasmalabs.models.ModelGenerators.GenHelper
import org.plasmalabs.models.generators.consensus.ModelGenerators.*
import org.plasmalabs.networking.NetworkGen.*
import org.plasmalabs.networking.multiplexer.MultiplexedReaderWriter
import org.scalamock.munit.AsyncMockFactory

import scala.concurrent.duration.*

class BlockchainSocketHandlerSpec extends CatsEffectSuite with AsyncMockFactory with ScalaCheckEffectSuite {

  type F[A] = IO[A]

  test("should provide a BlockchainPeerClient") {
    withMock {
      val testResource =
        for {
          server    <- mock[BlockchainPeerServerAlgebra[F]].pure[F].toResource
          buffers   <- BlockchainMultiplexedBuffers.make[F]
          readQueue <- Queue.unbounded[F, (Int, Bytes)].toResource
          readStream: Stream[F, (Int, Bytes)] = Stream.fromQueueUnterminated(readQueue)
          writer = mockFunction[(Int, Bytes), F[Unit]]
          writeF: Function1[(Int, Bytes), F[Unit]] = writer
          readerWriter = MultiplexedReaderWriter[F](read = readStream, write = (a: Int, b: Bytes) => writeF(a, b))
          cache        <- PeerStreamBuffer.make[F]
          requestMutex <- Mutex[F].toResource
          connectedPeer = arbitraryConnectedPeer.arbitrary.first
          requestTimeout = 3.seconds

          underTest = new BlockchainSocketHandler[F](
            server,
            buffers,
            readerWriter,
            cache,
            requestMutex,
            connectedPeer,
            requestTimeout
          )

          _ = (() => server.localBlockAdoptions).expects().once().returning(Stream.never[F].pure[F])
          _ = (() => server.localTransactionNotifications).expects().once().returning(Stream.never[F].pure[F])
          _ = writer.expects((BlockchainMultiplexerId.BlockAdoptionRequest.id, ZeroBS)).once().returning(().pure[F])
          _ = writer
            .expects((BlockchainMultiplexerId.TransactionNotificationRequest.id, ZeroBS))
            .once()
            .returning(().pure[F])

          _ <- underTest.client
            .evalMap(client =>
              for {
                // remotePeer test
                _ <- client.remotePeer.pure[F].assertEquals(connectedPeer)
                // blockIdAtDepth test
                blockId = arbitraryBlockId.arbitrary.first
                _ = writer
                  .expects(
                    (
                      BlockchainMultiplexerId.BlockIdAtDepthRequest.id,
                      ZeroBS.concat(Transmittable[Long].transmittableBytes(0L))
                    )
                  )
                  .once()
                  .returning(
                    readQueue.offer(
                      (
                        BlockchainMultiplexerId.BlockIdAtDepthRequest.id,
                        OneBS.concat(Transmittable[Option[BlockId]].transmittableBytes(blockId.some))
                      )
                    )
                  )
                _ <- client.getRemoteBlockIdAtDepth(0L).assertEquals(blockId.some)
                // blockIdAtHeight test
                _ = writer
                  .expects(
                    (
                      BlockchainMultiplexerId.BlockIdAtHeightRequest.id,
                      ZeroBS.concat(Transmittable[Long].transmittableBytes(1L))
                    )
                  )
                  .once()
                  .returning(
                    readQueue.offer(
                      (
                        BlockchainMultiplexerId.BlockIdAtHeightRequest.id,
                        OneBS.concat(Transmittable[Option[BlockId]].transmittableBytes(blockId.some))
                      )
                    )
                  )
                _ <- client.getRemoteBlockIdAtHeight(1L).assertEquals(blockId.some)
                // getRemoteHeader test
                header = arbitraryHeader.arbitrary.first
                _ = writer
                  .expects(
                    (
                      BlockchainMultiplexerId.HeaderRequest.id,
                      ZeroBS.concat(Transmittable[BlockId].transmittableBytes(blockId))
                    )
                  )
                  .once()
                  .returning(
                    readQueue.offer(
                      (
                        BlockchainMultiplexerId.HeaderRequest.id,
                        OneBS.concat(Transmittable[Option[BlockHeader]].transmittableBytes(header.some))
                      )
                    )
                  )
                _ <- client.getRemoteHeader(blockId).assertEquals(header.some)
              } yield ()
            )
            .compile
            .drain
            .toResource
        } yield ()

      testResource.use_
    }
  }
}
