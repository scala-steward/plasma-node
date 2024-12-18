package org.plasmalabs.indexer.algebras

import org.plasmalabs.consensus.models.{BlockHeader, BlockId}
import org.plasmalabs.indexer.model.GE
import org.plasmalabs.indexer.services.BlockData
import org.plasmalabs.node.models.BlockBody

/**
 * Algebra which defines fetch operations of blocks against the stored Ledger.
 *
 * @tparam F the effect-ful context to retrieve the value in
 */
trait BlockFetcherAlgebra[F[_]] {

  /**
   * Fetch Canonical head vertex on the stored indexer Ledger
   *
   * @return Optional header vertex, None if it was not found
   */
  def fetchCanonicalHead(): F[Either[GE, Option[BlockHeader]]]

  /**
   * Fetch a BlockHeader on the stored Ledger
   * @param blockId  blockId filter by field
   * @return Optional BlockHeader, None if it was not found
   */
  def fetchHeader(blockId: BlockId): F[Either[GE, Option[BlockHeader]]]

  /**
   * Fetch a BlockBody on the stored indexer Ledger
   *
   * @param blockId blockId filter by field
   * @return Optional BlockBody, None if it was not found
   */
  def fetchBody(blockId: BlockId): F[Either[GE, Option[BlockBody]]]

  /**
   * Fetch a Block on the stored indexer Ledger given a blockId
   *
   * @param blockId fetch a BlockData on the stored Ledger given a blockId
   * @return Optional BlockData, None if it was not found
   */
  def fetchBlock(blockId: BlockId): F[Either[GE, Option[BlockData]]]

  /**
   * Fetch a BlockHeader with height filter on the stored indexer Ledger
   *
   * @param height height filter by field
   * @return Optional BlockHeader, None if it was not found
   */
  def fetchHeaderByHeight(height: Long): F[Either[GE, Option[BlockHeader]]]

  /**
   * Fetch BlockData with height filter on the stored indexer Ledger
   *
   * @param height filter by field
   * @return Optional BlockData, None if it was not found
   */
  def fetchBlockByHeight(height: Long): F[Either[GE, Option[BlockData]]]

  /**
   * Fetch BlockData at the specified depth from the configured indexer service.
   *
   * @param depth filter by field
   * @return Optional BlockData, None if it was not found
   */
  def fetchBlockByDepth(depth: Long): F[Either[GE, Option[BlockData]]]

}
