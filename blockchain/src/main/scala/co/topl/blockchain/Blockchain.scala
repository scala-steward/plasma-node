package co.topl.blockchain

import akka.actor.typed.ActorSystem
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, Sink, Source}
import akka.util.ByteString
import cats.data.{OptionT, Validated}
import cats.effect._
import cats.implicits._
import cats.Parallel
import co.topl.algebras.{ClockAlgebra, Store, UnsafeResource}
import co.topl.catsakka._
import co.topl.codecs.bytes.tetra.instances._
import co.topl.codecs.bytes.typeclasses.implicits._
import co.topl.consensus.BlockHeaderOps
import co.topl.consensus.algebras.{BlockHeaderToBodyValidationAlgebra, BlockHeaderValidationAlgebra, LocalChainAlgebra}
import co.topl.eventtree.{EventSourcedState, ParentChildTree}
import co.topl.grpc.ToplGrpc
import co.topl.ledger.algebras._
import co.topl.minting.algebras.StakingAlgebra
import co.topl.models._
import co.topl.networking.blockchain._
import co.topl.networking.p2p.{ConnectedPeer, DisconnectedPeer, LocalPeer}
import co.topl.typeclasses.implicits._
import org.typelevel.log4cats.{Logger, SelfAwareStructuredLogger}
import BlockchainPeerHandler.monoidBlockchainPeerHandler
import co.topl.crypto.signing.Ed25519VRF
import co.topl.minting.{BlockPacker, BlockProducer}
import fs2.concurrent.Topic
import org.typelevel.log4cats.slf4j.Slf4jLogger
import scala.jdk.CollectionConverters._
import scala.util.Random

object Blockchain {

  /**
   * A program which executes the blockchain protocol, including a P2P layer, RPC layer, and minter.
   */
  def run[F[_]: Parallel: Async: FToFuture](
    clock:                       ClockAlgebra[F],
    staker:                      Option[StakingAlgebra[F]],
    slotDataStore:               Store[F, TypedIdentifier, SlotData],
    headerStore:                 Store[F, TypedIdentifier, BlockHeader],
    bodyStore:                   Store[F, TypedIdentifier, BlockBody],
    transactionStore:            Store[F, TypedIdentifier, Transaction],
    _localChain:                 LocalChainAlgebra[F],
    blockIdTree:                 ParentChildTree[F, TypedIdentifier],
    blockHeights:                EventSourcedState[F, Long => F[Option[TypedIdentifier]], TypedIdentifier],
    headerValidation:            BlockHeaderValidationAlgebra[F],
    blockHeaderToBodyValidation: BlockHeaderToBodyValidationAlgebra[F],
    transactionSyntaxValidation: TransactionSyntaxValidationAlgebra[F],
    bodySyntaxValidation:        BodySyntaxValidationAlgebra[F],
    bodySemanticValidation:      BodySemanticValidationAlgebra[F],
    bodyAuthorizationValidation: BodyAuthorizationValidationAlgebra[F],
    _mempool:                    MempoolAlgebra[F],
    ed25519VrfResource:          UnsafeResource[F, Ed25519VRF],
    localPeer:                   LocalPeer,
    remotePeers:                 Source[DisconnectedPeer, _],
    peerFlowModifier: (
      ConnectedPeer,
      Flow[ByteString, ByteString, F[BlockchainPeerClient[F]]]
    ) => Flow[ByteString, ByteString, F[BlockchainPeerClient[F]]],
    rpcHost:         String,
    rpcPort:         Int
  )(implicit system: ActorSystem[_], random: Random): F[Unit] = {
    implicit val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass[F](Blockchain.getClass)
    for {
      adoptionsTopic                           <- Topic[F, TypedIdentifier]
      (localChain, _localBlockAdoptionsSource) <- LocalChainBroadcaster.make(_localChain, adoptionsTopic)
      // todo check if queue is not blocked, should we try with subscribeAwait which return a Resource?
      toplRpcAdoptionConsumer   <- Async[F].delay(adoptionsTopic.subscribe(maxQueued = Int.MaxValue))
      localBlockAdoptionsSource <- _localBlockAdoptionsSource.toMat(BroadcastHub.sink)(Keep.right).liftTo[F]
      transactionAdoptionsTopic <- Topic[F, TypedIdentifier]
      mempool                   <- MempoolBroadcaster.make(_mempool, transactionAdoptionsTopic)
      mempoolTransactionAdoptionConsumer <- Async[F].delay(
        transactionAdoptionsTopic.subscribe(maxQueued = Int.MaxValue)
      )
      _localTransactionAdoptionsSource <- mempoolTransactionAdoptionConsumer.toAkkaSource
      localTransactionAdoptionsSource <- _localTransactionAdoptionsSource.toMat(BroadcastHub.sink)(Keep.right).liftTo[F]
      clientHandler =
        List(
          BlockchainPeerHandler.ChainSynchronizer.make[F](
            clock,
            localChain,
            headerValidation,
            blockHeaderToBodyValidation,
            bodySyntaxValidation,
            bodySemanticValidation,
            bodyAuthorizationValidation,
            slotDataStore,
            headerStore,
            bodyStore,
            transactionStore,
            blockIdTree
          ),
          BlockchainPeerHandler.FetchMempool.make(
            transactionSyntaxValidation,
            transactionStore,
            mempool
          ),
          BlockchainPeerHandler.CommonAncestorSearch.make(
            id =>
              OptionT(
                localChain.head
                  .map(_.slotId.blockId)
                  .flatMap(blockHeights.useStateAt(_)(_.apply(id)))
              ).toRight(new IllegalStateException("Unable to determine block height tree")).rethrowT,
            () => localChain.head.map(_.height),
            slotDataStore
          )
        ).combineAll
      peerServer <- BlockchainPeerServer.FromStores.make(
        slotDataStore,
        headerStore,
        bodyStore,
        transactionStore,
        blockHeights,
        localChain,
        localBlockAdoptionsSource
          .tapAsyncF(1)(id => Logger[F].debug(show"Broadcasting block id=$id to peer"))
          .pure[F],
        localTransactionAdoptionsSource
          .tapAsyncF(1)(id => Logger[F].debug(show"Broadcasting transaction id=$id to peer"))
          .pure[F]
      )
      (p2pServer, p2pFiber) <- BlockchainNetwork
        .make[F](
          localPeer.localAddress.getHostName,
          localPeer.localAddress.getPort,
          localPeer,
          remotePeers,
          clientHandler,
          peerServer,
          peerFlowModifier
        )
      blockPacker <- BlockPacker.make[F](
        mempool,
        transactionStore.getOrRaise,
        BlockPacker.makeBodyValidator(bodySyntaxValidation, bodySemanticValidation, bodyAuthorizationValidation)
      )
      mintedBlockStream <- staker.fold(Source.never[Block].pure[F])(staker =>
        // The BlockProducer needs a stream/Source of "parents" upon which it should build.  This stream is the
        // concatenation of the current local head with the stream of local block adoptions
        localChain.head
          .flatMap(currentHead =>
            BlockProducer
              .make[F](
                Source
                  .future(
                    implicitly[FToFuture[F]].apply(clock.delayedUntilSlot(currentHead.slotId.slot).as(currentHead))
                  )
                  .concat(localBlockAdoptionsSource.mapAsyncF(1)(slotDataStore.getOrRaise)),
                staker,
                clock,
                blockPacker
              )
          )
          .flatMap(_.blocks)
      )
      rpcInterpreter <- ToplRpcServer.make(
        headerStore,
        bodyStore,
        transactionStore,
        mempool,
        transactionSyntaxValidation,
        localChain,
        blockHeights,
        blockIdTree,
        toplRpcAdoptionConsumer
      )
      rpcServer = ToplGrpc.Server.serve(rpcHost, rpcPort, rpcInterpreter)
      mintedBlockStreamCompletionFuture =
        mintedBlockStream
          .tapAsyncF(1)(block => Logger[F].info(show"Minted header=${block.header} body=${block.body}"))
          .mapAsyncF(1)(block =>
            blockIdTree.associate(block.header.id, block.header.parentHeaderId) >>
            headerStore.put(block.header.id, block.header) >>
            bodyStore.put(block.header.id, block.body) >>
            ed25519VrfResource
              .use(implicit e => block.header.slotData.pure[F])
              .flatTap(slotDataStore.put(block.header.id, _))
          )
          .tapAsyncF(1)(slotData =>
            Logger[F].info(
              show"Adopted head block id=${slotData.slotId.blockId} height=${slotData.height} slot=${slotData.slotId.slot}"
            )
          )
          .map(Validated.Valid(_))
          .tapAsyncF(1)(localChain.adopt)
          .toMat(Sink.ignore)(Keep.right)
          .liftTo[F]
      _ <- rpcServer.use(binding =>
        Logger[F].info(s"RPC Server bound at ${binding.getListenSockets.asScala.toList.mkString(",")}") >>
        Async[F].fromFuture(mintedBlockStreamCompletionFuture) >>
        p2pFiber.join
      )
    } yield ()
  }

}
