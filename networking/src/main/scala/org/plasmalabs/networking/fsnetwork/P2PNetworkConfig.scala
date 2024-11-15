package org.plasmalabs.networking.fsnetwork

import org.plasmalabs.config.ApplicationConfig.Node.NetworkProperties

import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}

case class P2PNetworkConfig(networkProperties: NetworkProperties, slotDuration: FiniteDuration) {

  /**
   * Block providing novelty reputation if new unknown block is received in current slot.
   * Shall be always equal one.
   */
  val blockNoveltyInitialValue: Double = 1

  /**
   * Reducing block novelty reputation for each already known source, i.e:
   * blockNoveltyReputation = 1 - knewSourceForThatBlockId * blockNoveltyReputationStep
   */
  val blockNoveltyReputationStep: Double = 0.2

  /**
   * Block novelty reputation shall be reducing every slot by X number.
   * If we have reputation of "blockNoveltyInitialValue" then after "expectedSlotsPerBlock" slots that
   * reputation shall be equal to "blockNoveltyInitialValue" - "blockNoveltyReputationStep".
   * Thus we need such X number where:
   * pow(X, expectedSlotsPerBlock - 1) == "blockNoveltyInitialValue" - blockNoveltyReputationStep,
   * then:
   * X = root of (1 - blockNoveltyReputationStep) with index (expectedSlotsPerBlock - 1)
   */
  val blockNoveltyDecoy: Double =
    Math.pow(blockNoveltyInitialValue - blockNoveltyReputationStep, 1 / (networkProperties.expectedSlotsPerBlock - 1))

  /**
   * Maximum possible performance reputation, i.e. reputation for host with delay in 0 ms
   */
  val performanceReputationIdealValue: Double = 1

  /**
   * Any remote peer with "ping" equal or more than performanceReputationMaxDelay will have 0 performance reputation
   */
  val performanceReputationMaxDelay: Double = slotDuration.toMillis * networkProperties.maxPerformanceDelayInSlots

  /**
   * New remote peer will not be closed during "remotePeerNoveltyInSlots" slots even if reputation is low.
   * It gives a chance to build-up reputation for remote peer
   */
  val remotePeerNoveltyInSlots: Long =
    Math.ceil(networkProperties.expectedSlotsPerBlock * networkProperties.remotePeerNoveltyInExpectedBlocks).toLong

  /**
   * Each header download request increase peer novelty, it allows to keep connection if we sync from scratch.
   * It is required because of stopping processing new slot data from remote peer during processing already
   * received slot data, i.e. remote peer block providing reputation will be reduced to 0 over some time.
   */
  val maxPeerNovelty: Long =
    if (slotDuration.toMillis != 0)
      (1000L * 60 * 30) / slotDuration.toMillis // 30 minutes
    else
      remotePeerNoveltyInSlots

  /**
   * How often we update our list of warm hosts
   */
  val peersUpdateInterval: FiniteDuration =
    FiniteDuration(
      Math.ceil(networkProperties.warmHostsUpdateEveryNBlock * slotDuration.toMillis).toInt,
      MILLISECONDS
    )

  val aggressiveP2PRequestInterval: FiniteDuration = slotDuration * remotePeerNoveltyInSlots
}
