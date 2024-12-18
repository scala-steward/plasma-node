package org.plasmalabs.blockchain

import cats.data.EitherT
import cats.effect.Resource
import cats.effect.implicits.*
import cats.effect.kernel.Async
import cats.effect.std.Semaphore
import cats.implicits.*
import fs2.concurrent.Topic
import org.plasmalabs.algebras.Stats
import org.plasmalabs.codecs.bytes.tetra.instances.*
import org.plasmalabs.config.ApplicationConfig.Node.MempoolProtection
import org.plasmalabs.consensus.models.{BlockHeader, BlockId}
import org.plasmalabs.ledger.algebras.*
import org.plasmalabs.ledger.implicits.*
import org.plasmalabs.ledger.interpreters.QuivrContext
import org.plasmalabs.ledger.models.*
import org.plasmalabs.sdk.models.transaction.IoTransaction
import org.plasmalabs.sdk.models.{TransactionId, TransactionOutputAddress}
import org.plasmalabs.sdk.syntax.ioTransactionAsTransactionSyntaxOps
import org.plasmalabs.sdk.validation.algebras.{TransactionAuthorizationVerifier, TransactionCostCalculator}
import org.plasmalabs.typeclasses.implicits.*
import org.typelevel.log4cats.Logger

object MempoolProtected {

  def make[F[_]: Async: Logger: Stats](
    mempool:                          MempoolAlgebra[F],
    semanticValidationAlgebra:        TransactionSemanticValidationAlgebra[F],
    transactionAuthorizationVerifier: TransactionAuthorizationVerifier[F],
    currentBlockHeader:               F[BlockHeader],
    fetchTransaction:                 TransactionId => F[IoTransaction],
    transactionRewardCalculator:      TransactionRewardCalculatorAlgebra,
    txCostCalculator:                 TransactionCostCalculator,
    boxIdToHeight:                    TransactionOutputAddress => F[Option[Long]],
    config:                           MempoolProtection
  ): Resource[F, MempoolAlgebra[F]] =
    for {
      semaphore <- Semaphore[F](1).toResource
      _         <- Logger[F].info(s"Create protected mempool with parameters: $config").toResource
      memoryPool <- Async[F]
        .delay(
          new MempoolProtected(
            mempool,
            semanticValidationAlgebra,
            transactionAuthorizationVerifier,
            currentBlockHeader,
            fetchTransaction,
            transactionRewardCalculator,
            txCostCalculator,
            boxIdToHeight,
            config,
            semaphore
          )
        )
        .toResource
    } yield memoryPool

  class MempoolProtected[F[_]: Async: Logger: Stats](
    underlying:               MempoolAlgebra[F],
    semanticValidation:       TransactionSemanticValidationAlgebra[F],
    transactionAuthorization: TransactionAuthorizationVerifier[F],
    currentBlockHeader:       F[BlockHeader],
    fetchTransaction:         TransactionId => F[IoTransaction],
    txRewardCalculator:       TransactionRewardCalculatorAlgebra,
    txCostCalculator:         TransactionCostCalculator,
    boxIdToHeight:            TransactionOutputAddress => F[Option[Long]],
    config:                   MempoolProtection,
    semaphore:                Semaphore[F]
  ) extends MempoolAlgebra[F] {
    override def read(blockId: BlockId): F[MempoolGraph] = underlying.read(blockId)

    override def add(transactionId: TransactionId): F[Boolean] = semaphore.permit.use { _ =>
      for {
        header   <- currentBlockHeader
        graph    <- read(header.id)
        ioTx     <- fetchTransaction(transactionId)
        ioExTx   <- IoTransactionEx(ioTx, txRewardCalculator.rewardsOf(ioTx), txCostCalculator.costOf(ioTx)).pure[F]
        checkRes <- checkTransaction(header, graph, ioExTx)
        addRes <-
          if (checkRes) {
            underlying
              .add(transactionId)
              .flatTap(r => Logger[F].info(show"Added tx $transactionId to mempool with res $r"))
          } else {
            false.pure[F]
          }
      } yield addRes
    }

    override def remove(transactionId: TransactionId): F[Unit] =
      Logger[F].info(show"Received request to remove $transactionId") >> underlying.remove(transactionId)

    override def contains(blockId: BlockId, transactionId: TransactionId): F[Boolean] =
      underlying.contains(blockId, transactionId)

    private def checkTransaction(
      currentHeader: BlockHeader,
      graph:         MempoolGraph,
      transaction:   IoTransactionEx
    ): F[Boolean] = {
      val txId = transaction.tx.id

      if (!config.enabled || (graph.memSize + transaction.size) < config.protectionEnabledThreshold) {
        Logger[F].debug(show"Skip tx verification $txId") >>
        true.pure[F]
      } else {
        EitherT
          .right[String](transaction.pure[F])
          .flatMap(doSemanticCheck(currentHeader, graph))
          .flatMap(doAuthCheck(currentHeader))
          .flatMap(doFeeCheck(graph))
          .flatMap(doAgeCheck(currentHeader, graph))
          .value
          .recoverWith { case e =>
            Logger[F].debug(e)(show"Error during checking transaction in Mempool") >>
            Either.left[String, IoTransactionEx](e.toString).pure[F]
          }
          .flatMap {
            _.fold(
              e => Logger[F].info(show"Skip adding transaction to mempool $txId because of $e") >> false.pure[F],
              _ => Logger[F].debug(show"Transaction $txId could be added to mempool") >> true.pure[F]
            )
          }
      }
    }

    private def doSemanticCheck(currentHeader: BlockHeader, graph: MempoolGraph)(
      txToValidate: IoTransactionEx
    ): EitherT[F, String, IoTransactionEx] = {
      val semanticContextTemplate = StaticTransactionValidationContext(
        currentHeader.id,
        Seq.empty,
        currentHeader.height,
        currentHeader.slot
      )

      val semanticContext =
        if ((graph.memSize + txToValidate.size) < config.useMempoolForSemanticThreshold)
          semanticContextTemplate.copy(prefix = graph.ioTransactions.values.toSeq)
        else semanticContextTemplate

      EitherT(
        semanticValidation
          .validate(semanticContext)(txToValidate.tx)
          .map(_.toEither.bimap(_.mkString_(","), _ => txToValidate))
      )
    }

    private def doAuthCheck(
      currentHeader: BlockHeader
    )(txToValidate: IoTransactionEx): EitherT[F, String, IoTransactionEx] = {
      val authContext = QuivrContext.forProposedBlock(currentHeader.height, currentHeader.slot, txToValidate.tx)
      EitherT(
        transactionAuthorization
          .validate(authContext)(txToValidate.tx)
          .map(_.bimap(e => show"$e", _ => txToValidate))
      )
    }

    private def doFeeCheck(
      graph: MempoolGraph
    )(txToValidate: IoTransactionEx): EitherT[F, String, IoTransactionEx] =
      if ((graph.memSize + txToValidate.size) < config.feeFilterThreshold) {
        EitherT.right[String](txToValidate.pure[F])
      } else {
        val meanFeePerKByte = Math.max(1, graph.meanToplFeePerKByte)
        val freeMempoolSize = Math.max(0, config.maxMempoolSize - graph.memSize).toDouble
        val freeMempoolSizePercent = freeMempoolSize / config.maxMempoolSize
        val minimumFeePerKByte =
          if (freeMempoolSizePercent == 0) Double.MaxValue
          else (meanFeePerKByte / freeMempoolSizePercent) - meanFeePerKByte

        for {
          _ <- EitherT.liftF(
            Stats[F].recordGauge(
              "plasma_node_mempool_mean_fee_per_kb",
              "Average fee per kb in Topls.",
              Map(),
              longToJson(meanFeePerKByte.toLong)
            )
          )
          _ <- EitherT.liftF(
            Stats[F].recordGauge(
              "plasma_node_mempool_free_size",
              "Current free size of the mempool.",
              Map(),
              longToJson(freeMempoolSize.toLong)
            )
          )
          _ <- EitherT.liftF(
            Stats[F].recordGauge(
              "plasma_node_mempool_free_size_percent",
              "Current free size ratio of the mempool.",
              Map(),
              longToJson(freeMempoolSizePercent.toLong)
            )
          )
          _ <- EitherT.liftF(
            Stats[F].recordGauge(
              "plasma_node_mempool_minimum_fee_per_kilobyte",
              "Minimum fee per kb.",
              Map(),
              longToJson(minimumFeePerKByte.toLong)
            )
          )

          result <- Either
            .cond(
              test = txToValidate.toplFeePerKByte >= minimumFeePerKByte,
              right = txToValidate,
              left = show"Transaction ${txToValidate.tx.id} have fee ${txToValidate.toplFeePerKByte} per Kb " +
                show"but minimum acceptable fee is $minimumFeePerKByte per Kb"
            )
            .toEitherT[F]
        } yield (result)
      }

    private def doAgeCheck(currentHeader: BlockHeader, graph: MempoolGraph)(
      txToValidate: IoTransactionEx
    ): EitherT[F, String, IoTransactionEx] =
      if ((graph.memSize + txToValidate.size) < config.ageFilterThreshold) {
        EitherT.right[String](txToValidate.pure[F])
      } else {
        val meanAgeF =
          for {
            currentHeight <- currentHeader.height.pure[F]
            inputBoxes    <- txToValidate.tx.inputs.map(_.address).pure[F]
            inputHeights  <- inputBoxes.traverse(boxIdToHeight)
            meanAge <-
              if (inputHeights.isEmpty) 0L.pure[F]
              else (inputHeights.map(_.fold(0L)(currentHeight - _)).sum / inputHeights.size).pure[F]
          } yield meanAge

        val freeMempoolSize = Math.max(0, config.maxMempoolSize - graph.memSize)
        val freeAgeBasedMempoolSize = Math.max(0, config.maxMempoolSize - config.ageFilterThreshold)
        val minimumAge =
          config.maxOldBoxAge - ((freeMempoolSize.toDouble / freeAgeBasedMempoolSize) * config.maxOldBoxAge).toLong

        EitherT(meanAgeF.map { meanAge =>
          Either
            .cond(
              test = meanAge >= minimumAge,
              right = txToValidate,
              left = show"Transaction ${txToValidate.tx.id} have age $meanAge but minimum acceptable age is $minimumAge"
            )
        })
      }

    def adoptions: Topic[F, TransactionId] =
      underlying.adoptions
  }
}
