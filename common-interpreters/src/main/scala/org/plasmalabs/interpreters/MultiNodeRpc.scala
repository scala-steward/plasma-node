package org.plasmalabs.interpreters

import cats.Foldable
import cats.effect.Async
import cats.effect.std.{Random, SecureRandom}
import cats.implicits.*
import fs2.Stream
import org.plasmalabs.algebras.{NodeRpc, SynchronizationTraversalStep}
import org.plasmalabs.consensus.models.{BlockHeader, BlockId}
import org.plasmalabs.models.Epoch
import org.plasmalabs.node.models.BlockBody
import org.plasmalabs.proto.node.{EpochData, NodeConfig}
import org.plasmalabs.sdk.models.TransactionId
import org.plasmalabs.sdk.models.transaction.IoTransaction

object MultiNodeRpc {

  /**
   * Constructs an interpreter of `ToplRpc` that delegates requests to a collection
   * of sub-interpreters.  The delegate is chosen at random.
   * @param delegates a collection of ToplRpc interpreters
   * @tparam F an F-type constructor
   * @tparam G a collection type
   * @return a ToplRpc interpreter
   */
  def make[F[_]: Async, G[_]: Foldable](delegates: G[NodeRpc[F, Stream[F, _]]]): F[NodeRpc[F, Stream[F, _]]] =
    for {
      given Random[F] <- SecureRandom.javaSecuritySecureRandom[F]
    } yield new NodeRpc[F, Stream[F, _]] {

      private val delegatesArray =
        delegates.toIterable.toArray

      private def randomDelegate: F[NodeRpc[F, Stream[F, _]]] =
        Random[F]
          .nextIntBounded(delegatesArray.length)
          .map(delegatesArray(_))

      def broadcastTransaction(transaction: IoTransaction): F[Unit] =
        randomDelegate.flatMap(_.broadcastTransaction(transaction))

      def currentMempool(): F[Set[TransactionId]] =
        randomDelegate.flatMap(_.currentMempool())

      def currentMempoolContains(transactionId: TransactionId): F[Boolean] =
        randomDelegate.flatMap(_.currentMempoolContains(transactionId))

      def fetchBlockHeader(blockId: BlockId): F[Option[BlockHeader]] =
        randomDelegate.flatMap(_.fetchBlockHeader(blockId))

      def fetchBlockBody(blockId: BlockId): F[Option[BlockBody]] =
        randomDelegate.flatMap(_.fetchBlockBody(blockId))

      def fetchTransaction(transactionId: TransactionId): F[Option[IoTransaction]] =
        randomDelegate.flatMap(_.fetchTransaction(transactionId))

      def blockIdAtHeight(height: Long): F[Option[BlockId]] =
        randomDelegate.flatMap(_.blockIdAtHeight(height))

      def blockIdAtDepth(depth: Long): F[Option[BlockId]] =
        randomDelegate.flatMap(_.blockIdAtDepth(depth))

      def synchronizationTraversal(): F[Stream[F, SynchronizationTraversalStep]] =
        randomDelegate.flatMap(_.synchronizationTraversal())

      def fetchProtocolConfigs(): F[Stream[F, NodeConfig]] =
        randomDelegate.flatMap(_.fetchProtocolConfigs())

      def fetchEpochData(epoch: Option[Epoch]): F[Option[EpochData]] =
        randomDelegate.flatMap(_.fetchEpochData(epoch))

      override def fetchCanonicalHeadId(): F[Option[BlockId]] =
        randomDelegate.flatMap(_.fetchCanonicalHeadId())
    }
}
