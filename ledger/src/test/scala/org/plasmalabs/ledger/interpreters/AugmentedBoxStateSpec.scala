package org.plasmalabs.ledger.interpreters

import cats.data.NonEmptySet
import cats.effect.IO
import cats.implicits.*
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.plasmalabs.algebras.testInterpreters.TestStore
import org.plasmalabs.consensus.models.BlockId
import org.plasmalabs.eventtree.ParentChildTree
import org.plasmalabs.models.generators.consensus.ModelGenerators.*
import org.plasmalabs.node.models.BlockBody
import org.plasmalabs.sdk.constants.NetworkConstants
import org.plasmalabs.sdk.generators.ModelGenerators.*
import org.plasmalabs.sdk.models.*
import org.plasmalabs.sdk.models.transaction.*
import org.plasmalabs.sdk.syntax.*
import org.plasmalabs.typeclasses.implicits.*
import org.scalacheck.effect.PropF

class AugmentedBoxStateSpec extends CatsEffectSuite with ScalaCheckEffectSuite {

  test("BoxState includes new outputs and exclude spent outputs") {
    PropF.forAllF {
      (
        blockId0:         BlockId,
        txBase1:          IoTransaction,
        _txOutput10:      UnspentTransactionOutput,
        _txOutput11:      UnspentTransactionOutput,
        transaction2Base: IoTransaction,
        input:            SpentTransactionOutput,
        blockId1:         BlockId
      ) =>
        val txOutput10 =
          _txOutput10.copy(address =
            _txOutput10.address
              .withNetwork(NetworkConstants.PRIVATE_NETWORK_ID)
              .withLedger(NetworkConstants.MAIN_LEDGER_ID)
          )
        val txOutput11 =
          _txOutput11.copy(address =
            _txOutput11.address
              .withNetwork(NetworkConstants.PRIVATE_NETWORK_ID)
              .withLedger(NetworkConstants.MAIN_LEDGER_ID)
          )
        val transaction1 = txBase1.addOutputs(txOutput10, txOutput11)
        val outputBoxId10 = transaction1.id.outputAddress(
          NetworkConstants.PRIVATE_NETWORK_ID,
          NetworkConstants.MAIN_LEDGER_ID,
          transaction1.outputs.length - 2
        )
        val outputBoxId11 = transaction1.id.outputAddress(
          NetworkConstants.PRIVATE_NETWORK_ID,
          NetworkConstants.MAIN_LEDGER_ID,
          transaction1.outputs.length - 1
        )
        val transaction2 = transaction2Base.addInputs(input.copy(address = outputBoxId11))

        for {
          parentChildTree <- ParentChildTree.FromRef.make[IO, BlockId]
          (boxState, _) <- BoxState.make[IO](
            blockId0.pure[IO],
            Map(
              blockId1 ->
              BlockBody(List(transaction1.id, transaction2.id))
                .pure[IO]
            ).apply(_),
            Map(
              transaction1.id -> transaction1.pure[IO],
              transaction2.id -> transaction2.pure[IO]
            ),
            parentChildTree,
            _ => IO.unit,
            TestStore.make[IO, TransactionId, NonEmptySet[Short]].widen
          )

          stateAugmentation <-
            AugmentedBoxState.StateAugmentation.empty.augment(transaction1).augment(transaction2).pure[IO]

          underTest <- AugmentedBoxState.make(boxState)(stateAugmentation)

          _ <- underTest.boxExistsAt(blockId1)(outputBoxId10).assert
          _ <- underTest.boxExistsAt(blockId1)(outputBoxId11).map(!_).assert
        } yield ()
    }
  }
}
