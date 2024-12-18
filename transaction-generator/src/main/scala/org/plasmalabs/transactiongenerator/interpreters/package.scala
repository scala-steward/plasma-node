package org.plasmalabs.transactiongenerator

import org.plasmalabs.quivr.models.*
import org.plasmalabs.sdk.constants.NetworkConstants
import org.plasmalabs.sdk.models.*
import org.plasmalabs.sdk.models.box.*
import org.plasmalabs.sdk.models.transaction.IoTransaction
import org.plasmalabs.sdk.syntax.*
import org.plasmalabs.transactiongenerator.models.Wallet

package object interpreters {

  val HeightLockOneProposition: Proposition =
    Proposition(
      Proposition.Value.HeightRange(
        Proposition.HeightRange("header", 1, Long.MaxValue)
      )
    )

  val HeightLockOneChallenge: Challenge =
    Challenge().withRevealed(HeightLockOneProposition)

  val HeightLockOneLock: Lock =
    Lock(
      Lock.Value.Predicate(
        Lock.Predicate(
          List(HeightLockOneChallenge),
          1
        )
      )
    )

  val HeightLockOneSpendingAddress: LockAddress =
    HeightLockOneLock.lockAddress(
      NetworkConstants.PRIVATE_NETWORK_ID,
      NetworkConstants.MAIN_LEDGER_ID
    )

  val emptyWallet: Wallet =
    Wallet(
      Map.empty,
      Map(HeightLockOneSpendingAddress -> HeightLockOneLock)
    )

  /**
   * Incorporate a Transaction into a Wallet by removing spent outputs and including new outputs.
   */
  def applyTransaction(wallet: Wallet)(transaction: IoTransaction): Wallet = {
    val spentBoxIds = transaction.inputs.map(_.address)

    val transactionId = transaction.id
    val newBoxes =
      transaction.outputs.zipWithIndex.filter(_._1.value.value.isLvl).flatMap { case (output, index) =>
        wallet.propositions
          .get(output.address)
          .map(lock =>
            (
              // transactionId.outputAddress(NetworkConstants.PRIVATE_NETWORK_ID, NetworkConstants.MAIN_LEDGER_ID, index),
              transactionId
                .outputAddress(
                  output.address.network,
                  output.address.ledger,
                  index
                ),
              Box(lock, output.value)
            )
          )
      }
    wallet.copy(spendableBoxes = wallet.spendableBoxes -- spentBoxIds ++ newBoxes)
  }
}
