package org.plasmalabs.minting.interpreters

import cats.Monad
import cats.effect.*
import cats.effect.IO.asyncForIO
import cats.implicits.*
import com.google.protobuf.ByteString
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.plasmalabs.algebras.Stats.Implicits.*
import org.plasmalabs.consensus.algebras.*
import org.plasmalabs.consensus.models.*
import org.plasmalabs.consensus.thresholdEvidence
import org.plasmalabs.crypto.hash.Blake2b256
import org.plasmalabs.minting.algebras.{OperationalKeyMakerAlgebra, VrfCalculatorAlgebra}
import org.plasmalabs.minting.models.{OperationalKeyOut, VrfHit}
import org.plasmalabs.models.*
import org.plasmalabs.models.ModelGenerators.GenHelper
import org.plasmalabs.models.generators.consensus.ModelGenerators.*
import org.plasmalabs.models.utility.*
import org.plasmalabs.models.utility.HasLength.instances.*
import org.plasmalabs.models.utility.Lengths.*
import org.plasmalabs.sdk.models.{LockAddress, LockId}
import org.scalacheck.effect.PropF
import org.scalamock.munit.AsyncMockFactory
import scodec.bits.*

class StakingSpec extends CatsEffectSuite with ScalaCheckEffectSuite with AsyncMockFactory {
  type F[A] = IO[A]

  test("elect: with fixed values") {
    PropF.forAllF { (blockId: BlockId) =>
      withMock {
        val slot = 1L
        val parentSlotId = SlotId.of(slot, blockId)
        val eta = Sized.strictUnsafe(ByteString.copyFrom(Array.fill[Byte](32)(0))): Eta
        val relativeStake = Ratio.One
        val address = StakingAddress(ByteString.copyFrom(Array.fill[Byte](32)(0)))
        val rewardAddress = LockAddress(0, 0, LockId(ByteString.copyFrom(Array.fill[Byte](32)(0))))

        val vkVrf = ByteString.copyFrom(Array.fill[Byte](32)(0))
        val proof = ByteString.copyFrom(
          hex"bc31a2fb46995ffbe4b316176407f57378e2f3d7fee57d228a811194361d8e7040c9d15575d7a2e75506ffe1a47d772168b071a99d2e85511730e9c21397a1cea0e7fa4bd161e6d5185a94a665dd190d".toArray
        )
        val rho = Rho(
          Sized.strictUnsafe(
            data =
              hex"c30d2304d5d76e7cee8cc0eb66493528cc9e5a9cc03449bc8ed3dab192ba1e8edb3567b4ffc63526c69a6d05a73b57879529ccf8dd22e596080257843748d569"
          )
        )

        val etaCalculation = mock[EtaCalculationAlgebra[F]]
        val consensusState = mock[ConsensusValidationStateAlgebra[F]]
        val leaderElectionValidation = mock[LeaderElectionValidationAlgebra[F]]
        val vrfCalculator = mock[VrfCalculatorAlgebra[F]]

        (etaCalculation.etaToBe)
          .expects(parentSlotId, slot)
          .once()
          .returning(eta.pure[F])

        (consensusState
          .operatorRelativeStake(_: BlockId, _: Slot)(_: StakingAddress)(_: Monad[F]))
          .expects(blockId, slot, address, *)
          .once()
          .returning(relativeStake.some.pure[F])

        (leaderElectionValidation.getThreshold)
          .expects(relativeStake, slot - parentSlotId.slot)
          .once()
          .returning(Ratio.One.pure[F])

        (leaderElectionValidation
          .isSlotLeaderForThreshold(_: Ratio)(_: Rho))
          .expects(relativeStake, *)
          .once()
          .returning(true.pure[F])

        (vrfCalculator.proofForSlot)
          .expects(slot, eta)
          .twice()
          .returning(proof.pure[F])

        (vrfCalculator.rhoForSlot)
          .expects(slot, eta)
          .once()
          .returning(rho.pure[F])

        val resource = for {
          staking <- Staking
            .make[F](
              a = address,
              rewardAddress = rewardAddress,
              vkVrf,
              operationalKeyMaker = null,
              consensusState,
              etaCalculation,
              ed25519Resource = null,
              blake2b256Resource = Resource.pure(new Blake2b256),
              vrfCalculator,
              leaderElectionValidation
            )

          testProof <- vrfCalculator.proofForSlot(slot, eta).toResource

          expectedVrfHit = VrfHit(
            EligibilityCertificate(
              testProof,
              vkVrf,
              thresholdEvidence(relativeStake)(new Blake2b256),
              eta.data
            ),
            slot,
            relativeStake
          )
          _ <- staking.elect(parentSlotId, slot).assertEquals(expectedVrfHit.some).toResource
        } yield ()
        resource.use_
      }
    }
  }

  test("certifyBlock: with empty operationalKeyForSlot") {
    PropF.forAllF { (parentSlotId: SlotId, slot: Slot) =>
      withMock {
        val operationalKeyMaker = mock[OperationalKeyMakerAlgebra[F]]
        val eta = etaGen.first
        (operationalKeyMaker.operationalKeyForSlot)
          .expects(slot, parentSlotId, eta)
          .once()
          .returning(Option.empty[OperationalKeyOut].pure[F])

        val resource = for {
          staking <- Staking
            .make[F](
              a = null,
              rewardAddress = null,
              vkVrf = null,
              operationalKeyMaker,
              consensusState = null,
              etaCalculation = null,
              ed25519Resource = null,
              blake2b256Resource = null,
              vrfCalculator = null,
              leaderElectionValidation = null
            )

          _ <- staking
            .certifyBlock(parentSlotId, slot, _ => throw new NotImplementedError("unsignedBlockBuilder"), eta)
            .assertEquals(None)
            .toResource
        } yield ()
        resource.use_
      }
    }
  }

}
