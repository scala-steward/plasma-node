package co.topl.config

import co.topl.brambl.models.LockAddress
import co.topl.consensus.models.{BlockId, StakingAddress}
import co.topl.models.Slot
import co.topl.models.utility.Ratio
import co.topl.numerics.implicits._
import co.topl.proto.node.NodeConfig
import monocle.macros.Lenses

import scala.concurrent.duration._

// $COVERAGE-OFF$
@Lenses
case class ApplicationConfig(
  bifrost: ApplicationConfig.Bifrost,
  genus:   ApplicationConfig.Genus,
  kamon:   ApplicationConfig.Kamon
)

object ApplicationConfig {

  @Lenses
  case class Bifrost(
    data:                Bifrost.Data,
    staking:             Bifrost.Staking,
    p2p:                 Bifrost.P2P,
    rpc:                 Bifrost.RPC,
    mempool:             Bifrost.Mempool,
    bigBang:             Bifrost.BigBang,
    maxSupportedVersion: Int = 1,
    protocols:           Map[Slot, Bifrost.Protocol],
    cache:               Bifrost.Cache,
    ntp:                 Bifrost.Ntp,
    versionInfo:         Bifrost.VersionInfo
  )

  object Bifrost {

    @Lenses
    case class Data(directory: String, databaseType: String)

    @Lenses
    case class Staking(directory: String, rewardAddress: LockAddress, stakingAddress: Option[StakingAddress])

    @Lenses
    case class P2P(
      bindHost:          String,
      bindPort:          Int,
      publicHost:        Option[String],
      publicPort:        Option[Int],
      knownPeers:        List[KnownPeer],
      networkProperties: NetworkProperties
    )

    case class NetworkProperties(
      useHostNames:                         Boolean = false,
      pingPongInterval:                     FiniteDuration = 90.seconds,
      expectedSlotsPerBlock:                Double = 15.0, // TODO shall be calculated?
      maxPerformanceDelayInSlots:           Double = 2.0,
      remotePeerNoveltyInExpectedBlocks:    Double = 2.0,
      minimumBlockProvidingReputationPeers: Int = 2,
      minimumPerformanceReputationPeers:    Int = 2,
      // every slot we update txMempoolReputation as
      // newReputation = (currentReputation * (txImpactValue - 1) + txMempoolReputationForLastSlot) / txImpactValue
      txImpactRatio:                   Int = 1000,
      minimumTxMempoolReputationPeers: Int = 2,
      minimumRequiredReputation:       Double = 0.66,
      // any non-new peer require that reputation to be hot
      minimumBlockProvidingReputation: Double = 0.15,
      minimumEligibleColdConnections:  Int = 50,
      maximumEligibleColdConnections:  Int = 100,
      clearColdIfNotActiveForInMs:     Long = 7 * 24 * 60 * 60 * 1000, // 7 days
      minimumHotConnections:           Int = 7,
      maximumWarmConnections:          Int = 12,
      warmHostsUpdateEveryNBlock:      Double = 4.0,
      p2pTrackInterval:                FiniteDuration = 10.seconds,
      // we could try to connect to remote peer again after
      // closeTimeoutFirstDelayInMs * {number of closed connections in last closeTimeoutWindowInMs} ^ 2
      closeTimeoutFirstDelayInMs: Long = 1000,
      closeTimeoutWindowInMs:     Long = 1000 * 60 * 60 * 24, // 1 day
      aggressiveP2P:              Boolean = true, // always try to found new good remote peers
      aggressiveP2PCount:         Int = 1, // how many new connection will be opened
      // do not try to open aggressively connection to remote peer if we have closed N connection(s) to them recently
      aggressiveP2PMaxCloseEvent: Int = 3,
      defaultTimeout:             FiniteDuration = 3.seconds,
      chunkSize:                  Int = 1,
      // If remote peer have ip address in that list then that peer will not be exposed to other peers
      // Examples of supported ip addresses description:
      // 10.*.65-67.0/24 (first octet is "10", second octet is any, third octet in range 65-67, subnet mask is 24)
      // If subnet mask is used then first address in subnet shall be set, otherwise subnet mask will not be applied
      // In that case next addresses will be filtered: 10.45.67.0, 10.0.65.80, 10.255.66.200.
      // Next address is not filtered: 10.45.64.255
      // Could be used if current node serves as proxy, and we don't want to expose any node behind proxy
      doNotExposeIps: List[String] = List.empty,
      // If remote peer have id in that list then that peer will not be exposed to other peers
      // Could be used if current node serves as proxy, and we don't want to expose any node behind proxy
      doNotExposeIds: List[String] = List.empty,

      // How many parents shall be returned for getRemoteSlotDataWithParents request
      // Increasing number lead to fast sync from scratch from that peer but increased network usage
      slotDataParentDepth: Int = 25
    )

    case class KnownPeer(host: String, port: Int)

    @Lenses
    case class RPC(bindHost: String, bindPort: Int, networkControl: Boolean = false)

    @Lenses
    case class Mempool(defaultExpirationSlots: Long, protection: MempoolProtection = MempoolProtection())

    case class MempoolProtection(
      enabled: Boolean = false,
      // Use size in some abstract units which are used in co.topl.brambl.validation.algebras.TransactionCostCalculator
      maxMempoolSize: Long = 1024 * 1024 * 20,

      // do not perform mempool checks
      // if (protectionEnabledThresholdPercent / 100 * maxMempoolSize) is less than curren mempool size
      protectionEnabledThresholdPercent: Double = 10,

      // during semantic check we will include all transactions from memory pool in context
      // if (useMempoolForSemanticThresholdPercent / 100 * maxMempoolSize) is less than curren mempool size
      useMempoolForSemanticThresholdPercent: Double = 40,

      // Memory pool will accept transactions with fee starting from that threshold value,
      // required fee will increased if free memory pool size is decreased,
      // if free memory pool size is 0 then transaction with infinite fee will be required
      feeFilterThresholdPercent: Double = 50,

      // old box is required to be used as inputs starting from that threshold
      ageFilterThresholdPercent: Double = 75,

      // box with age (in height) maxOldBoxAge will always considered as old
      maxOldBoxAge: Long = 100000
    ) {
      val protectionEnabledThreshold = toMultiplier(protectionEnabledThresholdPercent) * maxMempoolSize
      val useMempoolForSemanticThreshold = toMultiplier(useMempoolForSemanticThresholdPercent) * maxMempoolSize
      val feeFilterThreshold = toMultiplier(feeFilterThresholdPercent) * maxMempoolSize
      val ageFilterThreshold = toMultiplier(ageFilterThresholdPercent) * maxMempoolSize
    }

    sealed abstract class BigBang

    object BigBangs {

      @Lenses
      case class Private(
        timestamp:        Long = System.currentTimeMillis() + 5_000L,
        stakerCount:      Int,
        stakes:           Option[List[BigInt]],
        localStakerIndex: Option[Int],
        regtestEnabled:   Boolean = false
      ) extends BigBang

      @Lenses
      case class Public(
        genesisId:  BlockId,
        sourcePath: String
      ) extends BigBang
    }

    @Lenses
    case class Protocol(
      minAppVersion:              String,
      fEffective:                 Ratio,
      vrfLddCutoff:               Int,
      vrfPrecision:               Int,
      vrfBaselineDifficulty:      Ratio,
      vrfAmplitude:               Ratio,
      slotGapLeaderElection:      Long,
      chainSelectionKLookback:    Long,
      slotDuration:               FiniteDuration,
      forwardBiasedSlotWindow:    Slot,
      operationalPeriodsPerEpoch: Long,
      kesKeyHours:                Int,
      kesKeyMinutes:              Int,
      epochLengthOverride:        Option[Long]
    ) {

      val chainSelectionSWindow: Long =
        (Ratio(chainSelectionKLookback, 4L) * fEffective.inverse).round.toLong

      val epochLength: Long =
        epochLengthOverride.getOrElse(((Ratio(chainSelectionKLookback) * fEffective.inverse) * 3).round.toLong)

      val operationalPeriodLength: Long =
        epochLength / operationalPeriodsPerEpoch

      val vrfCacheSize: Long =
        operationalPeriodLength * 4

      def nodeConfig(slot: Slot): NodeConfig = NodeConfig(
        slot = slot,
        slotDurationMillis = slotDuration.toMillis,
        epochLength = epochLength
      )

      def validation: Either[String, Unit] =
        for {
          _ <- Either.cond(epochLength % 3L == 0, (), s"Epoch length=$epochLength must be divisible by 3")
          _ <- Either.cond(
            epochLength % operationalPeriodsPerEpoch == 0,
            (),
            s"Epoch length=$epochLength must be divisible by $operationalPeriodsPerEpoch"
          )
        } yield ()
    }

    @Lenses
    case class Cache(
      parentChildTree:         Cache.CacheConfig,
      slotData:                Cache.CacheConfig,
      headers:                 Cache.CacheConfig,
      bodies:                  Cache.CacheConfig,
      transactions:            Cache.CacheConfig,
      spendableBoxIds:         Cache.CacheConfig,
      epochBoundaries:         Cache.CacheConfig,
      operatorStakes:          Cache.CacheConfig,
      registrations:           Cache.CacheConfig,
      blockHeightTree:         Cache.CacheConfig,
      eligibilities:           Cache.CacheConfig,
      epochData:               Cache.CacheConfig,
      registrationAccumulator: Cache.CacheConfig,
      txIdToBlockId:           Cache.CacheConfig,
      idToProposal:            Cache.CacheConfig,
      epochToCreatedVersion:   Cache.CacheConfig,
      versionVoting:           Cache.CacheConfig,
      epochToProposalIds:      Cache.CacheConfig,
      proposalVoting:          Cache.CacheConfig,
      epochToVersionIds:       Cache.CacheConfig,
      versionIdToProposal:     Cache.CacheConfig,
      versionCounter:          Cache.CacheConfig,
      containsCacheSize:       Long = 16384
    )

    object Cache {

      @Lenses
      case class CacheConfig(maximumEntries: Long, ttl: Option[FiniteDuration])
    }

    @Lenses
    case class Ntp(server: String, refreshInterval: FiniteDuration, timeout: FiniteDuration)

    @Lenses
    case class VersionInfo(enable: Boolean, uri: String, period: FiniteDuration)

  }

  @Lenses
  case class Genus(
    enable:            Boolean,
    orientDbDirectory: String,
    orientDbPassword:  String
  )

  @Lenses
  case class Kamon(enable: Boolean)
}
// $COVERAGE-ON$
