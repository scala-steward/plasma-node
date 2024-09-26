package co.topl.consensus.algebras

import co.topl.consensus.models.{BlockHeader, BlockHeaderValidationFailure}

trait BlockHeaderVotingValidationAlgebra[F[_]] {

  /**
   * Indicates if Block header contains correct voting information
   */
  def validate(header: BlockHeader): F[Either[BlockHeaderValidationFailure, BlockHeader]]
}