package co.topl.networking.fsnetwork

import cats.effect.{Async, Resource}
import co.topl.algebras.Store
import co.topl.brambl.models.TransactionId
import co.topl.brambl.models.transaction.IoTransaction
import co.topl.consensus.algebras._
import co.topl.consensus.models.{BlockHeader, BlockId, SlotData}
import co.topl.eventtree.ParentChildTree
import co.topl.ledger.algebras._
import co.topl.networking.blockchain.BlockchainPeerClient
import co.topl.networking.fsnetwork.BlockChecker.BlockCheckerActor
import co.topl.networking.fsnetwork.Notifier.NotifierActor
import co.topl.networking.fsnetwork.PeerActor.PeerActor
import co.topl.networking.fsnetwork.PeersManager.PeersManagerActor
import co.topl.networking.fsnetwork.ReputationAggregator.ReputationAggregatorActor
import co.topl.networking.fsnetwork.RequestsProxy.RequestsProxyActor
import co.topl.networking.p2p.RemoteAddress
import co.topl.node.models.BlockBody
import org.typelevel.log4cats.Logger

trait NetworkAlgebra[F[_]] {

  def makePeerManger(
    thisHostId:             HostId,
    networkAlgebra:         NetworkAlgebra[F],
    localChain:             LocalChainAlgebra[F],
    slotDataStore:          Store[F, BlockId, SlotData],
    transactionStore:       Store[F, TransactionId, IoTransaction],
    blockIdTree:            ParentChildTree[F, BlockId],
    headerToBodyValidation: BlockHeaderToBodyValidationAlgebra[F],
    newPeerCreationAlgebra: PeerCreationRequestAlgebra[F],
    p2pNetworkConfig:       P2PNetworkConfig,
    hotPeersUpdate:         Set[RemoteAddress] => F[Unit],
    savePeersFunction:      Set[RemoteAddress] => F[Unit]
  ): Resource[F, PeersManagerActor[F]]

  def makeReputationAggregation(
    peersManager:  PeersManagerActor[F],
    networkConfig: P2PNetworkConfig
  ): Resource[F, ReputationAggregatorActor[F]]

  def makeBlockChecker(
    reputationAggregator:        ReputationAggregatorActor[F],
    requestsProxy:               RequestsProxyActor[F],
    localChain:                  LocalChainAlgebra[F],
    slotDataStore:               Store[F, BlockId, SlotData],
    headerStore:                 Store[F, BlockId, BlockHeader],
    bodyStore:                   Store[F, BlockId, BlockBody],
    headerValidation:            BlockHeaderValidationAlgebra[F],
    bodySyntaxValidation:        BodySyntaxValidationAlgebra[F],
    bodySemanticValidation:      BodySemanticValidationAlgebra[F],
    bodyAuthorizationValidation: BodyAuthorizationValidationAlgebra[F],
    chainSelectionAlgebra:       ChainSelectionAlgebra[F, SlotData]
  ): Resource[F, BlockCheckerActor[F]]

  def makeRequestsProxy(
    reputationAggregator: ReputationAggregatorActor[F],
    peersManager:         PeersManagerActor[F],
    headerStore:          Store[F, BlockId, BlockHeader],
    bodyStore:            Store[F, BlockId, BlockBody]
  ): Resource[F, RequestsProxyActor[F]]

  def makeNotifier(
    peersManager:         PeersManagerActor[F],
    reputationAggregator: ReputationAggregatorActor[F],
    p2pNetworkConfig:     P2PNetworkConfig
  ): Resource[F, NotifierActor[F]]

  def makePeer(
    hostId:                 HostId,
    client:                 BlockchainPeerClient[F],
    requestsProxy:          RequestsProxyActor[F],
    reputationAggregator:   ReputationAggregatorActor[F],
    peersManager:           PeersManagerActor[F],
    localChain:             LocalChainAlgebra[F],
    slotDataStore:          Store[F, BlockId, SlotData],
    transactionStore:       Store[F, TransactionId, IoTransaction],
    blockIdTree:            ParentChildTree[F, BlockId],
    headerToBodyValidation: BlockHeaderToBodyValidationAlgebra[F]
  ): Resource[F, PeerActor[F]]
}

class NetworkAlgebraImpl[F[_]: Async: Logger: DnsResolver] extends NetworkAlgebra[F] {

  override def makePeerManger(
    thisHostId:             HostId,
    networkAlgebra:         NetworkAlgebra[F],
    localChain:             LocalChainAlgebra[F],
    slotDataStore:          Store[F, BlockId, SlotData],
    transactionStore:       Store[F, TransactionId, IoTransaction],
    blockIdTree:            ParentChildTree[F, BlockId],
    headerToBodyValidation: BlockHeaderToBodyValidationAlgebra[F],
    newPeerCreationAlgebra: PeerCreationRequestAlgebra[F],
    p2pNetworkConfig:       P2PNetworkConfig,
    hotPeersUpdate:         Set[RemoteAddress] => F[Unit],
    savePeersFunction:      Set[RemoteAddress] => F[Unit]
  ): Resource[F, PeersManagerActor[F]] =
    PeersManager.makeActor(
      thisHostId,
      networkAlgebra,
      localChain,
      slotDataStore,
      transactionStore,
      blockIdTree,
      headerToBodyValidation,
      newPeerCreationAlgebra,
      p2pNetworkConfig,
      hotPeersUpdate,
      savePeersFunction
    )

  override def makeReputationAggregation(
    peersManager:  PeersManagerActor[F],
    networkConfig: P2PNetworkConfig
  ): Resource[F, ReputationAggregatorActor[F]] =
    ReputationAggregator.makeActor(peersManager, networkConfig)

  override def makeBlockChecker(
    reputationAggregator:        ReputationAggregatorActor[F],
    requestsProxy:               RequestsProxyActor[F],
    localChain:                  LocalChainAlgebra[F],
    slotDataStore:               Store[F, BlockId, SlotData],
    headerStore:                 Store[F, BlockId, BlockHeader],
    bodyStore:                   Store[F, BlockId, BlockBody],
    headerValidation:            BlockHeaderValidationAlgebra[F],
    bodySyntaxValidation:        BodySyntaxValidationAlgebra[F],
    bodySemanticValidation:      BodySemanticValidationAlgebra[F],
    bodyAuthorizationValidation: BodyAuthorizationValidationAlgebra[F],
    chainSelectionAlgebra:       ChainSelectionAlgebra[F, SlotData]
  ): Resource[F, BlockCheckerActor[F]] =
    BlockChecker.makeActor(
      reputationAggregator,
      requestsProxy,
      localChain,
      slotDataStore,
      headerStore,
      bodyStore,
      headerValidation,
      bodySyntaxValidation,
      bodySemanticValidation,
      bodyAuthorizationValidation,
      chainSelectionAlgebra
    )

  override def makeRequestsProxy(
    reputationAggregator: ReputationAggregatorActor[F],
    peersManager:         PeersManagerActor[F],
    headerStore:          Store[F, BlockId, BlockHeader],
    bodyStore:            Store[F, BlockId, BlockBody]
  ): Resource[F, RequestsProxyActor[F]] =
    RequestsProxy.makeActor(reputationAggregator, peersManager, headerStore, bodyStore)

  override def makeNotifier(
    peersManager:         PeersManagerActor[F],
    reputationAggregator: ReputationAggregatorActor[F],
    p2pNetworkConfig:     P2PNetworkConfig
  ): Resource[F, NotifierActor[F]] =
    Notifier.makeActor(peersManager, reputationAggregator, p2pNetworkConfig)

  override def makePeer(
    hostId:                 HostId,
    client:                 BlockchainPeerClient[F],
    requestsProxy:          RequestsProxyActor[F],
    reputationAggregator:   ReputationAggregatorActor[F],
    peersManager:           PeersManagerActor[F],
    localChain:             LocalChainAlgebra[F],
    slotDataStore:          Store[F, BlockId, SlotData],
    transactionStore:       Store[F, TransactionId, IoTransaction],
    blockIdTree:            ParentChildTree[F, BlockId],
    headerToBodyValidation: BlockHeaderToBodyValidationAlgebra[F]
  ): Resource[F, PeerActor[F]] =
    PeerActor.makeActor(
      hostId,
      client,
      requestsProxy,
      reputationAggregator,
      peersManager,
      localChain,
      slotDataStore,
      transactionStore,
      blockIdTree,
      headerToBodyValidation
    )
}
