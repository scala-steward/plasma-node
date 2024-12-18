package org.plasmalabs.indexer.interpreter

import cats.effect.IO
import cats.implicits.*
import fs2.*
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.plasmalabs.algebras.NodeRpc
import org.plasmalabs.consensus.models.{BlockHeader, BlockId}
import org.plasmalabs.indexer.model.GEs.*
import org.plasmalabs.indexer.services.BlockData
import org.plasmalabs.models.generators.consensus.ModelGenerators.*
import org.plasmalabs.node.models.{BlockBody, FullBlockBody}
import org.plasmalabs.sdk.generators.ModelGenerators.*
import org.plasmalabs.sdk.models.TransactionId
import org.plasmalabs.sdk.models.transaction.IoTransaction
import org.plasmalabs.sdk.syntax.*
import org.scalacheck.effect.PropF
import org.scalamock.munit.AsyncMockFactory
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.collection.immutable.ListSet

class NodeBlockFetcherTest extends CatsEffectSuite with ScalaCheckEffectSuite with AsyncMockFactory {

  type F[A] = IO[A]
  implicit private val logger: Logger[F] = Slf4jLogger.getLoggerFromClass[F](this.getClass)
  private val nodeRpc: NodeRpc[F, Stream[F, *]] = mock[NodeRpc[F, Stream[F, *]]]

  private val nodeBlockFetcher = NodeBlockFetcher.make[F](nodeRpc, 1)

  test("On no block at given height, a None should be returned") {
    PropF.forAllF { (height: Long) =>
      withMock {

        (nodeRpc.blockIdAtHeight _)
          .expects(height)
          .returning(Option.empty[BlockId].pure[F])
          .once()

        val res = for {
          fetcher <- nodeBlockFetcher
          _ <- assertIO(
            fetcher.fetch(height),
            Option.empty[BlockData].asRight
          ).toResource

        } yield ()
        res.use_

      }
    }
  }

  test("On a block without a header, a Left of NoBlockHeaderFoundOnNode should be returned") {
    PropF.forAllF { (height: Long, blockId: BlockId) =>
      withMock {

        (nodeRpc.blockIdAtHeight _)
          .expects(height)
          .returning(blockId.some.pure[F])
          .once()

        (nodeRpc.fetchBlockHeader _)
          .expects(blockId)
          .returning(Option.empty[BlockHeader].pure[F])
          .once()

        (nodeRpc.fetchBlockBody _)
          .expects(blockId)
          .returning(BlockBody().some.pure[F])
          .once()

        val res = for {
          fetcher <- nodeBlockFetcher
          _ <- assertIO(
            fetcher.fetch(height),
            HeaderNotFound(blockId).asLeft
          ).toResource

        } yield ()
        res.use_

      }
    }
  }

  test("On a block without a body, a Left of NoBlockBodyFoundOnNode should be returned") {
    PropF.forAllF { (height: Long, blockId: BlockId, blockHeader: BlockHeader) =>
      withMock {

        (nodeRpc.blockIdAtHeight _)
          .expects(height)
          .returning(blockId.some.pure[F])
          .once()

        (nodeRpc.fetchBlockHeader _)
          .expects(blockId)
          .returning(blockHeader.some.pure[F])
          .once()

        (nodeRpc.fetchBlockBody _)
          .expects(blockId)
          .returning(Option.empty[BlockBody].pure[F])
          .once()

        val res = for {
          fetcher <- nodeBlockFetcher
          _ <- assertIO(
            fetcher.fetch(height),
            BodyNotFound(blockId).asLeft
          ).toResource

        } yield ()
        res.use_

      }
    }
  }

  test(
    "On a block with a transaction and missing it, " +
    "a Left of NonExistentTransactions with that txId should be returned"
  ) {
    PropF.forAllF {
      (
        height:        Long,
        blockId:       BlockId,
        blockHeader:   BlockHeader,
        transactionId: TransactionId
      ) =>
        withMock {

          val blockBody = BlockBody(Seq(transactionId))

          (nodeRpc.blockIdAtHeight _)
            .expects(height)
            .returning(blockId.some.pure[F])
            .once()

          (nodeRpc.fetchBlockHeader _)
            .expects(blockId)
            .returning(blockHeader.some.pure[F])
            .once()

          (nodeRpc.fetchBlockBody _)
            .expects(blockId)
            .returning(blockBody.some.pure[F])
            .once()

          (nodeRpc.fetchTransaction _)
            .expects(transactionId)
            .returning(Option.empty[IoTransaction].pure[F])
            .once()

          val res = for {
            fetcher <- nodeBlockFetcher
            _ <- assertIO(
              fetcher.fetch(height),
              TransactionsNotFound(ListSet(transactionId)).asLeft
            ).toResource

          } yield ()
          res.use_

        }
    }
  }

  test(
    "On a block with three transactions and missing two of them, " +
    "a Left of NonExistentTransactions with the first missing txId should be returned"
  ) {
    PropF.forAllF {
      (
        height:           Long,
        blockId:          BlockId,
        blockHeader:      BlockHeader,
        transactionId_01: TransactionId,
        transactionId_02: TransactionId,
        transactionId_03: TransactionId,
        transaction_01:   IoTransaction
      ) =>
        withMock {

          val blockBody = BlockBody(
            Seq(
              transactionId_01,
              transactionId_02,
              transactionId_03
            )
          )

          (nodeRpc.blockIdAtHeight _)
            .expects(height)
            .returning(blockId.some.pure[F])
            .once()

          (nodeRpc.fetchBlockHeader _)
            .expects(blockId)
            .returning(blockHeader.some.pure[F])
            .once()

          (nodeRpc.fetchBlockBody _)
            .expects(blockId)
            .returning(blockBody.some.pure[F])
            .once()

          (nodeRpc.fetchTransaction _)
            .expects(transactionId_01)
            .returning(transaction_01.some.pure[F])
            .once()

          (nodeRpc.fetchTransaction _)
            .expects(transactionId_02)
            .returning(Option.empty[IoTransaction].pure[F])
            .once()

          val res = for {
            fetcher <- nodeBlockFetcher
            _ <- assertIO(
              fetcher.fetch(height),
              TransactionsNotFound(
                ListSet(
                  transactionId_02
                )
              ).asLeft
            ).toResource

          } yield ()
          res.use_

        }
    }
  }

  test(
    "On a block with three transactions and missing all of them, " +
    "a Left of NonExistentTransactions with the first missing txId should be returned"
  ) {
    PropF.forAllF {
      (
        height:           Long,
        blockId:          BlockId,
        blockHeader:      BlockHeader,
        transactionId_01: TransactionId,
        transactionId_02: TransactionId,
        transactionId_03: TransactionId
      ) =>
        withMock {

          val blockBody = BlockBody(
            Seq(
              transactionId_01,
              transactionId_02,
              transactionId_03
            )
          )

          (nodeRpc.blockIdAtHeight _)
            .expects(height)
            .returning(blockId.some.pure[F])
            .once()

          (nodeRpc.fetchBlockHeader _)
            .expects(blockId)
            .returning(blockHeader.some.pure[F])
            .once()

          (nodeRpc.fetchBlockBody _)
            .expects(blockId)
            .returning(blockBody.some.pure[F])
            .once()

          (nodeRpc.fetchTransaction _)
            .expects(transactionId_01)
            .returning(Option.empty[IoTransaction].pure[F])
            .once()

          val res = for {
            fetcher <- nodeBlockFetcher
            _ <- assertIO(
              fetcher.fetch(height),
              TransactionsNotFound(
                ListSet(
                  transactionId_01
                )
              ).asLeft
            ).toResource

          } yield ()
          res.use_

        }
    }
  }

  test(
    "On a block with a header and three transactions, a Right of the full block body should be returned"
  ) {
    PropF.forAllF {
      (
        height:         Long,
        blockId:        BlockId,
        blockHeader:    BlockHeader,
        transaction_01: IoTransaction,
        transaction_02: IoTransaction,
        transaction_03: IoTransaction
      ) =>
        withMock {

          val transactionId_01 = transaction_01.id
          val transactionId_02 = transaction_02.id
          val transactionId_03 = transaction_03.id

          val blockBody = BlockBody(
            Seq(
              transactionId_01,
              transactionId_02,
              transactionId_03
            )
          )

          val fullBlockBody = FullBlockBody(
            Seq(
              transaction_01,
              transaction_02,
              transaction_03
            )
          )

          (nodeRpc.blockIdAtHeight _)
            .expects(height)
            .returning(blockId.some.pure[F])
            .once()

          (nodeRpc.fetchBlockHeader _)
            .expects(blockId)
            .returning(blockHeader.some.pure[F])
            .once()

          (nodeRpc.fetchBlockBody _)
            .expects(blockId)
            .returning(blockBody.some.pure[F])
            .once()

          (nodeRpc.fetchTransaction _)
            .expects(transactionId_01)
            .returning(transaction_01.some.pure[F])
            .once()

          (nodeRpc.fetchTransaction _)
            .expects(transactionId_02)
            .returning(transaction_02.some.pure[F])
            .once()

          (nodeRpc.fetchTransaction _)
            .expects(transactionId_03)
            .returning(transaction_03.some.pure[F])
            .once()

          val res = for {
            fetcher <- nodeBlockFetcher
            _ <- assertIO(
              fetcher.fetch(height),
              BlockData(
                header = blockHeader,
                body = fullBlockBody
              ).some.asRight
            ).toResource

          } yield ()
          res.use_

        }
    }
  }

  test("On no block at fetchCanonicalHeadId, a None should be returned") {
    withMock {
      (() => nodeRpc.fetchCanonicalHeadId())
        .expects()
        .returning(Option.empty[BlockId].pure[F])
        .once()

      val res = for {
        fetcher <- nodeBlockFetcher
        _ <- assertIO(
          fetcher.fetchCanonicalHeadId(),
          Option.empty[BlockId]
        ).toResource

      } yield ()
      res.use_
    }
  }

  test("On no block at fetchCanonicalHeadId, a blockId should be returned") {
    PropF.forAllF { (blockId: BlockId) =>
      withMock {
        (() => nodeRpc.fetchCanonicalHeadId())
          .expects()
          .returning(blockId.some.pure[F])
          .once()

        val res = for {
          fetcher <- nodeBlockFetcher
          _ <- assertIO(
            fetcher.fetchCanonicalHeadId(),
            blockId.some
          ).toResource

        } yield ()
        res.use_

      }
    }
  }

}
