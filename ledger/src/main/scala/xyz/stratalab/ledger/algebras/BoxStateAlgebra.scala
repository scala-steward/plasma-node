package xyz.stratalab.ledger.algebras

import xyz.stratalab.consensus.models.BlockId
import xyz.stratalab.sdk.models.TransactionOutputAddress

trait BoxStateAlgebra[F[_]] {

  /**
   * Indicates if a particular box is spendable at the given block ID
   * @return F[true] if the box exists and is spendable
   *         F[false] if the box either never existed or has been spent already.  The interpretation should make no
   *         distinction between the two scenarios.
   */
  def boxExistsAt(blockId: BlockId)(boxId: TransactionOutputAddress): F[Boolean]
}
