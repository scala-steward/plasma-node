package org.plasmalabs.consensus.interpreters

import cats.data.NonEmptyChain
import cats.effect.*
import cats.implicits.*
import cats.{MonadThrow, Parallel}
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.protobuf.ByteString
import org.plasmalabs.algebras.ClockAlgebra.implicits.*
import org.plasmalabs.algebras.{ClockAlgebra, Stats}
import org.plasmalabs.consensus.algebras.EtaCalculationAlgebra
import org.plasmalabs.consensus.models.{BlockId, EtaCalculationArgs, SlotData, SlotId}
import org.plasmalabs.consensus.rhoToRhoNonceHash
import org.plasmalabs.crypto.hash.{Blake2b256, Blake2b512}
import org.plasmalabs.models.*
import org.plasmalabs.models.utility.HasLength.instances.*
import org.plasmalabs.models.utility.{Sized, *}
import org.plasmalabs.typeclasses.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import scalacache.Entry
import scalacache.caffeine.CaffeineCache

object EtaCalculation {

  /**
   * The configuration for the cache containing computed eta values.  Only one eta is computed per epoch (per tine),
   * so the cache size doesn't need to be particularly large.
   */
  private val caffeineCacheBuilder = Caffeine.newBuilder.maximumSize(32)

  def make[F[_]: Sync: Parallel: Stats](
    fetchSlotData:      BlockId => F[SlotData],
    clock:              ClockAlgebra[F],
    genesisEta:         Eta,
    blake2b256Resource: Resource[F, Blake2b256],
    blake2b512Resource: Resource[F, Blake2b512]
  ): F[EtaCalculationAlgebra[F]] =
    for {
      given CaffeineCache[F, Bytes, Eta] <- Sync[F].delay(
        CaffeineCache(caffeineCacheBuilder.build[Bytes, Entry[Eta]]())
      )
      slotsPerEpoch <- clock.slotsPerEpoch
      impl = new Impl[F](fetchSlotData, clock, genesisEta, slotsPerEpoch, blake2b256Resource, blake2b512Resource)
    } yield impl

  private class Impl[F[_]: Sync: Parallel](
    fetchSlotData:      BlockId => F[SlotData],
    clock:              ClockAlgebra[F],
    genesisEta:         Eta,
    slotsPerEpoch:      Long,
    blake2b256Resource: Resource[F, Blake2b256],
    blake2b512Resource: Resource[F, Blake2b512]
  )(implicit cache: CaffeineCache[F, Bytes, Eta])
      extends EtaCalculationAlgebra[F] {

    implicit private val logger: Logger[F] = Slf4jLogger.getLoggerFromClass(this.getClass)

    private val twoThirdsLength = slotsPerEpoch * 2 / 3

    override def etaToBe(parentSlotId: SlotId, childSlot: Slot): F[Eta] =
      clock
        .epochOf(childSlot)
        .flatMap(childEpoch =>
          if (childEpoch === 0L) genesisEta.pure[F]
          else
            for {
              parentEpoch    <- clock.epochOf(parentSlotId.slot)
              parentSlotData <- fetchSlotData(parentSlotId.blockId)
              eta <-
                if (parentEpoch === childEpoch) Sized.strictUnsafe[Bytes, Eta.Length](parentSlotData.eta).pure[F]
                else if (childEpoch - parentEpoch > 1)
                  MonadThrow[F].raiseError(
                    new IllegalStateException(
                      show"Eta calculation encountered empty epoch for" +
                      show" parentSlotId=$parentSlotId" +
                      show" childSlot=$childSlot" +
                      show" parentEpoch=$parentEpoch" +
                      show" childEpoch=$childEpoch"
                    )
                  )
                else locateTwoThirdsBest(parentSlotData).flatMap(calculate)
            } yield eta
        )

    /**
     * Given some header near the end of an epoch, traverse the chain (toward genesis) until reaching a block
     * that is inside of the 2/3 window of the epoch
     */
    private def locateTwoThirdsBest(from: SlotData): F[SlotData] =
      if (isWithinTwoThirds(from)) from.pure[F]
      else from.iterateUntilM(data => fetchSlotData(data.parentSlotId.blockId))(isWithinTwoThirds)

    private def isWithinTwoThirds(from: SlotData): Boolean =
      from.slotId.slot % slotsPerEpoch <= twoThirdsLength

    /**
     * Compute the Eta value for the epoch containing the given header
     * @param twoThirdsBest The latest block header in some tine, but within the first 2/3 of the epoch
     */
    private def calculate(twoThirdsBest: SlotData): F[Eta] =
      cache.cachingF(twoThirdsBest.slotId.blockId.value)(ttl = None)(
        Sync[F].defer(
          for {
            epoch      <- clock.epochOf(twoThirdsBest.slotId.slot)
            epochRange <- clock.epochRange(epoch)
            epochData <- NonEmptyChain(twoThirdsBest).iterateUntilM(items =>
              fetchSlotData(items.head.parentSlotId.blockId).map(items.prepend)
            )(items => items.head.parentSlotId.slot < epochRange.start)
            rhoValues = epochData.map(slotData => Rho(Sized.strictUnsafe(slotData.rho)))
            nextEta <- calculate(
              previousEta = Sized.strictUnsafe[Bytes, Eta.Length](twoThirdsBest.eta),
              epoch = epoch + 1,
              rhoValues = rhoValues
            )
          } yield nextEta
        )
      )

    /**
     * Calculate a new Eta value once all the necessary pre-requisites have been gathered
     */
    private def calculate(
      previousEta: Eta,
      epoch:       Epoch,
      rhoValues:   NonEmptyChain[Rho]
    ): F[Eta] =
      for {
        _ <- Logger[F].debug(
          show"Calculating new eta." +
          show" previousEta=$previousEta" +
          show" epoch=$epoch" +
          show" rhoValues=[${rhoValues.length}]{${rhoValues.head}..${rhoValues.last}}"
        )
        rhoNonceHashes <- rhoValues
          .map(_.sizedBytes.data)
          .parTraverse(rho =>
            blake2b512Resource
              .use(implicit b2b => Sync[F].delay(rhoToRhoNonceHash(rho)))
              .map(nonceHashBytes => RhoNonceHash(Sized.strictUnsafe(nonceHashBytes)))
          )
        nextEta <- calculateFromNonceHashValues(previousEta, epoch, rhoNonceHashes)
        _ <- Logger[F].info(
          show"Calculated new eta." +
          show" previousEta=$previousEta" +
          show" epoch=$epoch" +
          show" rhoValues=[${rhoValues.length}]{${rhoValues.head}..${rhoValues.last}}" +
          show" nextEta=$nextEta"
        )
      } yield nextEta

    /**
     * Calculate a new Eta value once all the necessary pre-requisites have been gathered
     */
    private def calculateFromNonceHashValues(
      previousEta:        Eta,
      epoch:              Epoch,
      rhoNonceHashValues: NonEmptyChain[RhoNonceHash]
    ): F[Eta] =
      Sync[F]
        .delay(EtaCalculationArgs(previousEta, epoch, rhoNonceHashValues.toIterable).digestMessages)
        .flatMap(bytes => blake2b256Resource.use(b2b => Sync[F].delay(b2b.hash(bytes.map(v => v: Array[Byte])*))))
        .map(v => v: ByteString)
        .map(Sized.strictUnsafe(_): Eta)

  }
}
