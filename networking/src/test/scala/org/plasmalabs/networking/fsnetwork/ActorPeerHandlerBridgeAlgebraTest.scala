package org.plasmalabs.networking.fsnetwork

import cats.data.{NonEmptyChain, Validated, ValidatedNec}
import cats.effect.kernel.Sync
import cats.effect.{Async, IO, Ref, Resource}
import cats.implicits.*
import cats.{Applicative, MonadThrow}
import fs2.Stream
import fs2.concurrent.Topic
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.plasmalabs.algebras.Stats.Implicits.*
import org.plasmalabs.algebras.testInterpreters.TestStore
import org.plasmalabs.blockchain.{BlockchainCore, DataStores, Validators}
import org.plasmalabs.config.ApplicationConfig.Node.NetworkProperties
import org.plasmalabs.consensus.Consensus
import org.plasmalabs.consensus.algebras.*
import org.plasmalabs.consensus.models.*
import org.plasmalabs.crypto.signing.Ed25519VRF
import org.plasmalabs.eventtree.ParentChildTree
import org.plasmalabs.interpreters.SchedulerClock
import org.plasmalabs.ledger.Ledger
import org.plasmalabs.ledger.algebras.*
import org.plasmalabs.ledger.models.*
import org.plasmalabs.models.ModelGenerators.GenHelper
import org.plasmalabs.models.generators.consensus.ModelGenerators.arbitrarySlotData
import org.plasmalabs.models.p2p.*
import org.plasmalabs.models.utility.NetworkCommands
import org.plasmalabs.networking.blockchain.{BlockchainPeerClient, NetworkProtocolVersions}
import org.plasmalabs.networking.fsnetwork.ActorPeerHandlerBridgeAlgebraTest.*
import org.plasmalabs.networking.fsnetwork.BlockDownloadError.BlockBodyOrTransactionError
import org.plasmalabs.networking.fsnetwork.TestHelper.arbitraryHost
import org.plasmalabs.networking.p2p.{ConnectedPeer, DisconnectedPeer, PeerConnectionChange}
import org.plasmalabs.node.models.*
import org.plasmalabs.quivr.runtime.DynamicContext
import org.plasmalabs.sdk.generators.ModelGenerators.arbitraryIoTransaction
import org.plasmalabs.sdk.models.transaction.IoTransaction
import org.plasmalabs.sdk.models.{Datum, TransactionId}
import org.plasmalabs.sdk.syntax.ioTransactionAsTransactionSyntaxOps
import org.plasmalabs.sdk.validation.TransactionSyntaxError
import org.plasmalabs.sdk.validation.algebras.TransactionSyntaxVerifier
import org.plasmalabs.typeclasses.implicits.*
import org.scalamock.munit.AsyncMockFactory
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}

object ActorPeerHandlerBridgeAlgebraTest {
  type F[A] = IO[A]

  val genesisSlotData: SlotData = arbitrarySlotData.arbitrary.first.copy(height = 0)

  val defaultChainSelectionAlgebra: ChainSelectionAlgebra[F, BlockId, SlotData] =
    new ChainSelectionAlgebra[F, BlockId, SlotData] {

      override def compare(
        x:        SlotData,
        y:        SlotData,
        xFetcher: BlockId => F[SlotData],
        yFetcher: BlockId => F[SlotData]
      ): F[Int] =
        x.height.compare(y.height).pure[F]

      override def enoughHeightToCompare(currentHeight: Long, commonHeight: Long, proposedHeight: Long): F[Long] =
        proposedHeight.pure[F]
    }

  val networkProperties: NetworkProperties = NetworkProperties()

  val headerValidation: BlockHeaderValidationAlgebra[F] = new BlockHeaderValidationAlgebra[F] {

    override def validate(header: BlockHeader): F[Either[BlockHeaderValidationFailure, BlockHeader]] =
      Either.right[BlockHeaderValidationFailure, BlockHeader](header).pure[F]

    override def couldBeValidated(header: BlockHeader, currentHead: SlotData): F[Boolean] = true.pure[F]
  }

  val headerToBodyValidation: BlockHeaderToBodyValidationAlgebra[F] =
    (block: Block) => Either.right[BlockHeaderToBodyValidationFailure, Block](block).pure[F]

  val defaultTxSyntaxValidator: TransactionSyntaxVerifier[F] =
    new TransactionSyntaxVerifier[F] {

      override def validate(t: IoTransaction): F[Either[NonEmptyChain[TransactionSyntaxError], IoTransaction]] =
        t.asRight[NonEmptyChain[TransactionSyntaxError]].pure[F]
    }

  val bodySyntaxValidation: BodySyntaxValidationAlgebra[F] =
    (blockBody: BlockBody) => Validated.validNec[BodySyntaxError, BlockBody](blockBody).pure[F]

  val bodySemanticValidation: BodySemanticValidationAlgebra[F] = new BodySemanticValidationAlgebra[F] {

    def validate(context: BodyValidationContext)(blockBody: BlockBody): F[ValidatedNec[BodySemanticError, BlockBody]] =
      Validated.validNec[BodySemanticError, BlockBody](blockBody).pure[F]
  }

  val bodyAuthorizationValidation: BodyAuthorizationValidationAlgebra[F] = new BodyAuthorizationValidationAlgebra[F] {

    override def validate(context: IoTransaction => DynamicContext[F, String, Datum])(
      blockBody: BlockBody
    ): F[ValidatedNec[BodyAuthorizationError, BlockBody]] =
      Validated.validNec[BodyAuthorizationError, BlockBody](blockBody).pure[F]
  }

  def createSlotDataStore: F[TestStore[F, BlockId, SlotData]] =
    TestStore.make[F, BlockId, SlotData]

  def createHeaderStore: F[TestStore[F, BlockId, BlockHeader]] =
    TestStore.make[F, BlockId, BlockHeader]

  def createBodyStore: F[TestStore[F, BlockId, BlockBody]] =
    TestStore.make[F, BlockId, BlockBody]

  def createTransactionStore: F[TestStore[F, TransactionId, IoTransaction]] =
    TestStore.make[F, TransactionId, IoTransaction]

  def createRemotePeerStore: F[TestStore[F, Unit, Seq[KnownRemotePeer]]] =
    TestStore.make[F, Unit, Seq[KnownRemotePeer]]

  def createBlockIdTree: F[ParentChildTree[F, BlockId]] =
    ParentChildTree.FromRef.make[F, BlockId]

  def createEmptyLocalChain: LocalChainAlgebra[F] = new LocalChainAlgebra[F] {
    private var currentHead: SlotData = genesisSlotData

    override def isWorseThan(newHead: NonEmptyChain[SlotData]): F[Boolean] =
      (newHead.last.height > currentHead.height).pure[F]

    override def adopt(newHead: Validated.Valid[SlotData]): F[Unit] =
      (currentHead = newHead.a).pure[F]

    override def head: F[SlotData] =
      currentHead.copy().pure[F]

    override def genesis: F[SlotData] = genesisSlotData.pure[F]

    override def adoptions: F[Stream[F, BlockId]] = Stream.empty.covaryAll[F, BlockId].pure[F]

    override def blockIdAtHeight(height: Long): F[Option[BlockId]] = ???
  }

  class MempoolExt[F[_]: Async](transactions: Ref[F, Set[TransactionId]]) extends MempoolAlgebra[F] {
    override def read(blockId: BlockId): F[MempoolGraph] = ???

    override def add(transactionId: TransactionId): F[Boolean] = transactions.update(_ + transactionId) >> true.pure[F]

    override def remove(transactionId: TransactionId): F[Unit] = transactions.update(_ - transactionId)

    override def contains(blockId: BlockId, transactionId: TransactionId): F[Boolean] =
      transactions.get.map(_.contains(transactionId))

    def contains(transactionId: TransactionId): F[Boolean] = transactions.get.map(_.contains(transactionId))

    def size: F[Int] = transactions.get.map(_.size)

    override def adoptions: Topic[F, TransactionId] = ???
  }

  val slotLength: FiniteDuration = FiniteDuration(200, MILLISECONDS)
  val forwardBiasedSlotWindow = 10L
  val slotsPerEpoch: Long = 100L
  val slotsPerOperationalPeriod: Long = 20L
}

class ActorPeerHandlerBridgeAlgebraTest extends CatsEffectSuite with ScalaCheckEffectSuite with AsyncMockFactory {
  implicit val dummyDns: DnsResolver[F] = (host: String) => Option(host).pure[F]
  implicit val dummyReverseDns: ReverseDnsResolver[F] = (h: String) => h.pure[F]
  implicit val logger: Logger[F] = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)
  val hostId: HostId = arbitraryHost.arbitrary.first

  test("Start network with add one network peer: adoption shall be started, peers shall be saved in the end") {
    withMock {
      val remotePeerPort = 9085
      val remotePeerAddress = RemoteAddress("2.2.2.2", remotePeerPort)
      val remotePeerVK = arbitraryHost.arbitrary.first.id
      val remotePeerId = HostId(remotePeerVK)
      val remoteConnectedPeer = ConnectedPeer(remotePeerAddress, remotePeerVK, NetworkProtocolVersions.V1)
      val remotePeer = DisconnectedPeer(remotePeerAddress, Some(remotePeerVK))
      val remotePeers: List[DisconnectedPeer] = List(remotePeer)
      val hotPeersUpdatedFlag: AtomicBoolean = new AtomicBoolean(false)
      val pingProcessedFlag: AtomicBoolean = new AtomicBoolean(false)

      val localChainMock = createEmptyLocalChain

      val client =
        mock[BlockchainPeerClient[F]]
      (client.notifyAboutThisNetworkLevel).expects(true).returns(Applicative[F].unit)
      (client.getPongMessage).expects(*).anyNumberOfTimes().onCall { (req: PingMessage) =>
        Sync[F].delay(pingProcessedFlag.set(true)) >>
        Option(PongMessage(req.ping.reverse)).pure[F]
      }
      (client.getRemoteBlockIdAtHeight)
        .expects(1)
        .returns(localChainMock.genesis.map(sd => Option(sd.slotId.blockId)))
      (() => client.remotePeer)
        .expects()
        .anyNumberOfTimes()
        .returns(remoteConnectedPeer)

      (() => client.remotePeerAdoptions).expects().once().onCall { () =>
        Stream.fromOption[F](Option.empty[BlockId]).pure[F]
      }

      val totalTransactions = 10
      val transactions = Seq.fill(totalTransactions)(arbitraryIoTransaction.arbitrary.first).map(_.embedId)
      (() => client.remoteTransactionNotifications).expects().once().onCall { () =>
        Stream.emits(transactions.map(_.id)).covary[F].pure[F]
      }
      val transactionsMap = transactions.map(tx => tx.id -> tx).toMap
      (client
        .getRemoteTransactionOrError(_: TransactionId, _: BlockBodyOrTransactionError)(_: MonadThrow[F]))
        .expects(*, *, *)
        .repeat(transactions.length)
        .onCall { case (id: TransactionId, _: BlockBodyOrTransactionError @unchecked, _: MonadThrow[F] @unchecked) =>
          transactionsMap(id).pure[F]
        }

      (() => client.remotePeerAsServer)
        .expects()
        .returns(Option(KnownHost(remotePeerVK, remotePeerAddress.host, remotePeerAddress.port)).pure[F])
      (client.getRemoteKnownHosts)
        .expects(*)
        .anyNumberOfTimes()
        .returns(Option(CurrentKnownHostsRes(Seq.empty)).pure[F])
      (client.notifyAboutThisNetworkLevel).expects(false).returns(Applicative[F].unit)
      ((() => client.closeConnection())).expects().returns(Applicative[F].unit)

      val peerOpenRequested: AtomicBoolean = new AtomicBoolean(false)
      val addRemotePeer: DisconnectedPeer => F[Unit] = mock[DisconnectedPeer => F[Unit]]
      (addRemotePeer.apply).expects(remotePeer).once().returns {
        Sync[F].delay(peerOpenRequested.set(true))
      }

      var hotPeers: Set[RemotePeer] = Set.empty
      val hotPeersUpdate: Set[RemotePeer] => F[Unit] = mock[Set[RemotePeer] => F[Unit]]
      (hotPeersUpdate.apply).expects(*).anyNumberOfTimes().onCall { (peers: Set[RemotePeer]) =>
        (if (peers.nonEmpty)
           Sync[F].delay(hotPeersUpdatedFlag.set(true))
         else
           Applicative[F].unit) *>
        (hotPeers = peers).pure[F]
      }

      val execResource = for {
        mempool <- Ref.of[F, Set[TransactionId]](Set()).toResource
        topic   <- Resource.make[F, Topic[F, PeerConnectionChange]](Topic[F, PeerConnectionChange])(_.close.void)
        mempoolAlgebra = new MempoolExt[F](mempool)
        slotDataStore    <- createSlotDataStore.toResource
        headerStore      <- createHeaderStore.toResource
        bodyStore        <- createBodyStore.toResource
        transactionStore <- createTransactionStore.toResource
        remotePeersStore <- createRemotePeerStore.toResource
        blockIdTree      <- createBlockIdTree.toResource
        localChain = localChainMock
        clockAlgebra <- SchedulerClock.make[F](
          slotLength,
          slotsPerEpoch,
          slotsPerOperationalPeriod,
          Instant.ofEpochMilli(System.currentTimeMillis()),
          forwardBiasedSlotWindow,
          () => 0L.pure[F]
        )

        blockchain = {
          val dataStores = mock[DataStores[F]]
          (() => dataStores.slotData).expects().anyNumberOfTimes().returning(slotDataStore)
          (() => dataStores.headers).expects().anyNumberOfTimes().returning(headerStore)
          (() => dataStores.bodies).expects().anyNumberOfTimes().returning(bodyStore)
          (() => dataStores.transactions).expects().anyNumberOfTimes().returning(transactionStore)
          (() => dataStores.knownHosts).expects().anyNumberOfTimes().returning(remotePeersStore)

          val consensus = mock[Consensus[F]]
          (() => consensus.localChain).expects().anyNumberOfTimes().returning(localChain)
          (() => consensus.chainSelection).expects().anyNumberOfTimes().returning(defaultChainSelectionAlgebra)

          val ledger = mock[Ledger[F]]
          (() => ledger.mempool).expects().anyNumberOfTimes().returning(mempoolAlgebra)

          val validators = mock[Validators[F]]
          (() => validators.header).expects().anyNumberOfTimes().returning(headerValidation)
          (() => validators.headerToBody).expects().anyNumberOfTimes().returning(headerToBodyValidation)
          (() => validators.transactionSyntax).expects().anyNumberOfTimes().returning(defaultTxSyntaxValidator)
          (() => validators.bodySyntax).expects().anyNumberOfTimes().returning(bodySyntaxValidation)
          (() => validators.bodySemantics).expects().anyNumberOfTimes().returning(bodySemanticValidation)
          (() => validators.bodyAuthorization).expects().anyNumberOfTimes().returning(bodyAuthorizationValidation)

          val b = mock[BlockchainCore[F]]
          (() => b.dataStores).expects().anyNumberOfTimes().returning(dataStores)
          (() => b.blockIdTree).expects().anyNumberOfTimes().returning(blockIdTree)
          (() => b.clock).expects().anyNumberOfTimes().returning(clockAlgebra)
          (() => b.consensus).expects().anyNumberOfTimes().returning(consensus)
          (() => b.ledger).expects().anyNumberOfTimes().returning(ledger)
          (() => b.validators).expects().anyNumberOfTimes().returning(validators)
          b
        }

        networkCommandsTopic <- Resource.make(Topic[F, NetworkCommands])(_.close.void)
        algebra <- ActorPeerHandlerBridgeAlgebra
          .make[F](
            hostId,
            blockchain,
            networkProperties,
            remotePeers,
            topic,
            addRemotePeer,
            hotPeersUpdate,
            Resource.pure(Ed25519VRF.precomputed()),
            networkCommandsTopic
          )
      } yield (algebra, remotePeersStore, mempoolAlgebra)

      val timeout = FiniteDuration(100, MILLISECONDS)
      for {
        (_, remotePeersStore, mempoolAlgebra) <- execResource
          .evalTap { case (algebra, _, mempoolAlgebra) =>
            Sync[F].untilM_(Sync[F].sleep(timeout))(Sync[F].delay(peerOpenRequested.get())) *>
            Stream
              .resource(algebra.usePeer(client))
              .mergeHaltR(
                Stream.exec(
                  for {
                    _ <- Sync[F].untilM_(Sync[F].sleep(timeout))(Sync[F].delay(pingProcessedFlag.get()))
                    _ <- Sync[F].untilM_(Sync[F].sleep(timeout))(Sync[F].delay(hotPeersUpdatedFlag.get()))
                    _ <- Sync[F]
                      .untilM_(Sync[F].sleep(timeout))(Sync[F].defer(mempoolAlgebra.size).map(_ == transactions.size))
                  } yield ()
                )
              )
              .compile
              .drain
          }
          .use(_.pure[F])
        _ = assert(hotPeers.contains(RemotePeer(remotePeerId, remotePeerAddress)))
        savedPeers <- remotePeersStore.get(()).map(_.get)
        _ = assert(savedPeers.map(_.address).contains(remotePeerAddress))
        savedPeer = savedPeers.find(_.address == remotePeerAddress).get
        _ = assert(savedPeer.perfReputation != 0.0)
        txsContains <- transactions.map(_.id).traverse(mempoolAlgebra.contains)
        _ = assert(txsContains.forall(identity))
      } yield ()
    }

  }
}
