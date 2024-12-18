package org.plasmalabs.consensus

import org.plasmalabs.consensus.algebras.*
import org.plasmalabs.consensus.models.{BlockId, SlotData}

trait Consensus[F[_]] {
  def headerValidation: BlockHeaderValidationAlgebra[F]
  def headerToBodyValidation: BlockHeaderToBodyValidationAlgebra[F]
  def chainSelection: ChainSelectionAlgebra[F, BlockId, SlotData]
  def consensusValidationState: ConsensusValidationStateAlgebra[F]
  def etaCalculation: EtaCalculationAlgebra[F]
  def leaderElection: LeaderElectionValidationAlgebra[F]
  def localChain: LocalChainAlgebra[F]
}

case class ConsensusImpl[F[_]](
  headerValidation:         BlockHeaderValidationAlgebra[F],
  headerToBodyValidation:   BlockHeaderToBodyValidationAlgebra[F],
  chainSelection:           ChainSelectionAlgebra[F, BlockId, SlotData],
  consensusValidationState: ConsensusValidationStateAlgebra[F],
  etaCalculation:           EtaCalculationAlgebra[F],
  leaderElection:           LeaderElectionValidationAlgebra[F],
  localChain:               LocalChainAlgebra[F]
) extends Consensus[F]
