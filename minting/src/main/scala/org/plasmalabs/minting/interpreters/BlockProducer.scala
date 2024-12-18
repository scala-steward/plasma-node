package org.plasmalabs.minting.interpreters

import cats.data.OptionT
import cats.effect.*
import cats.implicits.*
import com.google.protobuf.ByteString
import fs2.*
import org.plasmalabs.algebras.ClockAlgebra.implicits.*
import org.plasmalabs.algebras.{ClockAlgebra, Stats}
import org.plasmalabs.catsutils.*
import org.plasmalabs.codecs.bytes.tetra.instances.*
import org.plasmalabs.consensus.interpreters.*
import org.plasmalabs.consensus.interpreters.CrossEpochEventSourceState.VotingData
import org.plasmalabs.consensus.models.{BlockId, ProtocolVersion, SlotData, SlotId, StakingAddress}
import org.plasmalabs.eventtree.EventSourcedState
import org.plasmalabs.ledger.algebras.TransactionRewardCalculatorAlgebra
import org.plasmalabs.minting.algebras.{BlockPackerAlgebra, BlockProducerAlgebra, StakingAlgebra}
import org.plasmalabs.minting.models.VrfHit
import org.plasmalabs.models.*
import org.plasmalabs.models.utility.HasLength.instances.byteStringLength
import org.plasmalabs.models.utility.Sized
import org.plasmalabs.node.models.{BlockBody, FullBlock, FullBlockBody}
import org.plasmalabs.quivr.models.SmallData
import org.plasmalabs.sdk.models.*
import org.plasmalabs.sdk.models.box.*
import org.plasmalabs.sdk.models.transaction.*
import org.plasmalabs.sdk.syntax.*
import org.plasmalabs.typeclasses.implicits.*
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.{Logger, SelfAwareStructuredLogger}

import scala.concurrent.duration.*

object BlockProducer {

  /**
   * Creates a BlockProducerAlgebra which emits blocks whenever the staker is eligible.  Eligibility is determined
   * by the parent block header (among other factors), so any time the canonical head changes, the BlockProducer abandons
   * any work it was doing previously and starts building from the new parent header.  Each new parent header results
   * in a new "next" eligibility/slot.  The BlockProducer will give as much time as possible to the Block Packer
   * before instructing the Block Packer to provide its current best result and forming it into a Block.
   * @param parentHeaders a stream of Block Headers (slot data), where each Block Header is the current canonical head
   *                      of the chain
   * @param blockPacker a function which returns a Source that should emit a single element when demanded.  The Source
   *                    should immediately start constructing a result once created, and it should emit its best attempt
   *                    when demanded.
   * @param constructionPermit an effect which semantically blocks until permission to produce a new block is provided.
   *                      Under normal chains, this is a no-op (permission immediately provided).
   *                      Under regtest mode, this semantically blocks until an RPC is invoked granting permission
   */
  def make[F[_]: Async: Stats](
    parentHeaders:       Stream[F, SlotData],
    staker:              StakingAlgebra[F],
    clock:               ClockAlgebra[F],
    blockPacker:         BlockPackerAlgebra[F],
    rewardCalculator:    TransactionRewardCalculatorAlgebra,
    constructionPermit:  F[Unit],
    crossEpochForkLocal: EventSourcedState[F, VotingData[F], BlockId],
    votedVersionF:       F[VersionId],
    votedProposalF:      F[ProposalId]
  ): F[BlockProducerAlgebra[F]] =
    (staker.address, Ref.of(0L)).mapN((address, lastUsedSlotRef) =>
      new Impl[F](
        address,
        parentHeaders,
        staker,
        clock,
        blockPacker,
        rewardCalculator,
        lastUsedSlotRef,
        constructionPermit,
        crossEpochForkLocal,
        votedVersionF,
        votedProposalF
      )
    )

  private class Impl[F[_]: Async: Stats](
    stakerAddress:       StakingAddress,
    parentHeaders:       Stream[F, SlotData],
    staker:              StakingAlgebra[F],
    clock:               ClockAlgebra[F],
    blockPacker:         BlockPackerAlgebra[F],
    rewardCalculator:    TransactionRewardCalculatorAlgebra,
    lastUsedSlotRef:     Ref[F, Slot],
    constructionPermit:  F[Unit],
    crossEpochForkLocal: EventSourcedState[F, VotingData[F], BlockId],
    votedVersionF:       F[VersionId],
    votedProposalF:      F[ProposalId]
  ) extends BlockProducerAlgebra[F] {

    implicit private val logger: SelfAwareStructuredLogger[F] =
      Slf4jLogger.getLoggerFromName[F]("Node.BlockProducer")

    val blocks: F[Stream[F, FullBlock]] =
      Sync[F].delay(
        parentHeaders
          .through(AbandonerPipe(makeChild))
          .evalTap(block => lastUsedSlotRef.set(block.header.slot))
      )

    /**
     * Construct a new child Block of the given parent
     */
    private def makeChild(parentSlotData: SlotData): F[FullBlock] =
      Async[F].onCancel(
        makeChildImpl(parentSlotData),
        Async[F].defer(Logger[F].info(show"Abandoned block attempt on parentId=${parentSlotData.slotId.blockId}"))
      )

    private def makeChildImpl(parentSlotData: SlotData): F[FullBlock] =
      (
        for {
          _ <- Logger[F].info(
            show"Starting block attempt on" +
            show" parentId=${parentSlotData.slotId.blockId}" +
            show" parentSlot=${parentSlotData.slotId.slot}" +
            show" parentHeight=${parentSlotData.height}"
          )
          lastUsedSlot <- lastUsedSlotRef.get
          child        <- attemptUntilCertified(parentSlotData)(lastUsedSlot.max(parentSlotData.slotId.slot) + 1)
        } yield child
      )
        .handleErrorWith(e =>
          Logger[F].error(e)(
            show"Block production failed.  Retrying in 1 second." +
            show" parentId=${parentSlotData.slotId.blockId}" +
            show" parentSlot=${parentSlotData.slotId.slot}" +
            show" parentHeight=${parentSlotData.height}"
          ) >> Async[F].delayBy(makeChildImpl(parentSlotData), 1.seconds)
        )

    /**
     * Attempts to produce a new block.  If the staker is eligible but no operational key is available, the attempt
     * will be retried starting in the next operational period.
     */
    private def attemptUntilCertified(parentSlotData: SlotData)(fromSlot: Slot): F[FullBlock] =
      for {
        parentEpoch <- clock.epochOf(parentSlotData.slotId.slot)
        maxSlot     <- clock.epochRange(parentEpoch + 1).map(_.last)
        nextHit     <- nextEligibility(parentSlotData.slotId)(fromSlot, maxSlot)
        _ <- Logger[F].info(
          show"Packing block for" +
          show" parentId=${parentSlotData.slotId.blockId}" +
          show" parentSlot=${parentSlotData.slotId.slot}" +
          show" eligibilitySlot=${nextHit.slot}"
        )
        parentId = parentSlotData.slotId.blockId
        // Assemble the transactions to be placed in our new block
        fullBody <- packBlock(parentId, parentSlotData.height + 1, nextHit.slot)
        // Assign the block's timestamp to the current time, unless it falls outside the window for the target slot
        timestamp <- (clock.slotToTimestamps(nextHit.slot), clock.currentTimestamp)
          .mapN((boundary, currentTimestamp) => currentTimestamp.min(boundary.last).max(boundary.head))
        epoch         <- clock.epochOf(nextHit.slot)
        headerVersion <- crossEpochForkLocal.useStateAt(parentId)(_.versionAlgebra.getVersionForEpoch(epoch))
        votedVersion  <- votedVersionF
        votedProposal <- votedProposalF
        protocolVersion = ProtocolVersion(
          versionId = headerVersion,
          votedVersionId = votedVersion,
          votedProposalId = votedProposal
        )

        blockMaker = prepareUnsignedBlock(parentSlotData, fullBody, timestamp, nextHit, protocolVersion)
        eta: Eta = Sized.strictUnsafe[ByteString, Eta.Length](nextHit.cert.eta)
        _           <- Logger[F].info("Certifying block")
        maybeHeader <- staker.certifyBlock(parentSlotData.slotId, nextHit.slot, blockMaker, eta)
        _           <- Logger[F].debug(show"Created maybe block header ${maybeHeader.map(_.id)}")
        result <- OptionT
          .fromOption[F](maybeHeader)
          .map(FullBlock(_, fullBody))
          .semiflatTap(block =>
            Sync[F]
              .delay(BlockBody(block.fullBody.transactions.map(_.id), block.fullBody.rewardTransaction.map(_.id)))
              .flatMap(body => Logger[F].info(show"Minted header=${block.header} body=$body")) >>
            Stats[F].recordHistogram(
              "plasma_node_blocks_minted",
              "Blocks minted",
              Map(),
              longToJson(block.header.height)
            )
          )
          // Despite being eligible, there may not be a corresponding linear KES key if the node restarted in the middle
          // of an operational period.  The node must wait until the next operational period
          // to have a set of corresponding linear keys use.
          .getOrElseF(
            for {
              operationalPeriodLength <- clock.slotsPerOperationalPeriod
              nextOperationalPeriodSlot <- Sync[F]
                .delay((nextHit.slot / operationalPeriodLength + 1) * operationalPeriodLength)
              _ <- Logger[F]
                .warn(
                  show"Operational key unavailable.  Skipping eligibility at slot=${nextHit.slot}" +
                  show" plus any remaining eligibilities until next operational period at slot=$nextOperationalPeriodSlot"
                )
              res <- attemptUntilCertified(parentSlotData)(nextOperationalPeriodSlot)
            } yield res
          )
      } yield result

    /**
     * Determine the staker's next eligibility based on the given parent
     */
    private def nextEligibility(parentSlotId: SlotId)(fromSlot: Slot, maxSlot: Slot): F[VrfHit] =
      OptionT(
        fs2.Stream
          .range[F, Slot](
            fromSlot.max(parentSlotId.slot + 1L),
            maxSlot
          )
          .evalMap(staker.elect(parentSlotId, _))
          .collectFirst { case Some(value) => value }
          .compile
          .last
      ).getOrElseF(
        Logger[F].warn("No remaining eligibilities within epoch boundaries.  Waiting for block from remote peer.") >>
        Async[F].never[VrfHit]
      )

    /**
     * Launch the block packer function, then delay the clock, then stop the block packer function and
     * capture the result.  The goal is to grant as much time as possible to the block packer function to produce
     * the best possible block.
     *
     * @param untilSlot The slot at which the block packer function should be halted and a value extracted
     */
    private def packBlock(parentId: BlockId, height: Long, untilSlot: Slot): F[FullBlockBody] =
      OptionT(
        (blockPacker.blockImprover(parentId, height, untilSlot) ++ Stream.never)
          .interruptWhen(
            constructionPermit >>
            clock.delayedUntilSlot(untilSlot) >>
            Logger[F].info(s"Capturing packed block at slot=$untilSlot") >>
            ().asRight[Throwable].pure[F]
          )
          .compile
          .last
      ).getOrElse(FullBlockBody())
        .flatTap(_ => Logger[F].info(s"Captured packed block at slot=$untilSlot"))
        .flatMap(insertReward(parentId, untilSlot, _))

    /**
     * Calculate the total block reward quantity, and insert a Reward Transaction into the given FullBlockBody
     * @param parentBlockId the ID of the parent block.  This ID will be used in a fake IoTransaction reference
     *                      as input to the reward transaction
     * @param slot The slot in which the block will be produced.  The resulting transaction is only valid at this slot
     * @param base The unrewarded block body
     * @return a new block body
     */
    private def insertReward(parentBlockId: BlockId, slot: Slot, base: FullBlockBody): F[FullBlockBody] =
      base.transactions
        .foldMapM(t => rewardCalculator.rewardsOf(t).pure[F])
        .flatMap(rewardQuantities =>
          if (!rewardQuantities.isEmpty) {
            staker.rewardAddress
              .map(rewardAddress =>
                base.withRewardTransaction(
                  IoTransaction(datum =
                    Datum.IoTransaction(
                      Event
                        .IoTransaction(
                          schedule = Schedule(min = slot, max = slot),
                          metadata = SmallData.defaultInstance
                        )
                    )
                  )
                    .withInputs(
                      List(
                        SpentTransactionOutput(
                          address = TransactionOutputAddress(id = TransactionId(parentBlockId.value)),
                          attestation = Attestation.defaultInstance,
                          value = Value.defaultInstance
                        )
                      )
                    )
                    .withOutputs(
                      (
                        List(rewardQuantities.lvl)
                          .filter(_ > 0)
                          .map(Value.LVL(_))
                          .map(Value.defaultInstance.withLvl(_)) ++
                        List(rewardQuantities.topl)
                          .filter(_ > 0)
                          .map(Value.TOPL(_))
                          .map(Value.defaultInstance.withTopl(_)) ++
                        rewardQuantities.assets.toList
                          .filter(_._2 > 0)
                          .map { case (assetId, quantity) =>
                            Value.defaultInstance.withAsset(
                              Value.Asset(
                                assetId.groupId,
                                assetId.seriesId,
                                quantity,
                                assetId.groupAlloy,
                                assetId.seriesAlloy,
                                assetId.fungibilityType,
                                assetId.quantityDescriptor
                              )
                            )
                          }
                      )
                        .map(UnspentTransactionOutput(rewardAddress, _))
                    )
                )
              )
              .flatTap(_ => Logger[F].info(show"Collecting block reward=$rewardQuantities"))
          } else {
            // To avoid dust accumulation for 0-reward blocks, don't include a reward transaction
            base.clearRewardTransaction
              .pure[F]
              .flatTap(_ => Logger[F].info("No rewards to collect for block"))
          }
        )

    /**
     * After the block body has been constructed, prepare a Block Header for signing
     */
    private def prepareUnsignedBlock(
      parentSlotData:  SlotData,
      body:            FullBlockBody,
      timestamp:       Timestamp,
      nextHit:         VrfHit,
      protocolVersion: ProtocolVersion
    ): UnsignedBlockHeader.PartialOperationalCertificate => UnsignedBlockHeader =
      (partialOperationalCertificate: UnsignedBlockHeader.PartialOperationalCertificate) =>
        UnsignedBlockHeader(
          parentHeaderId = parentSlotData.slotId.blockId,
          parentSlot = parentSlotData.slotId.slot,
          txRoot = body.merkleTreeRootHash.data,
          bloomFilter = body.bloomFilter.data,
          timestamp = timestamp,
          height = parentSlotData.height + 1,
          slot = nextHit.slot,
          eligibilityCertificate = nextHit.cert,
          partialOperationalCertificate = partialOperationalCertificate,
          metadata = ByteString.EMPTY,
          address = stakerAddress,
          protocolVersion = protocolVersion
        )
  }

}
