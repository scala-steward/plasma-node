package org.plasmalabs.networking.fsnetwork

import cats.MonadThrow
import cats.data.{NonEmptyChain, OptionT}
import cats.effect.kernel.{Async, Fiber}
import cats.effect.{Resource, Spawn}
import cats.implicits.*
import fs2.Stream
import org.plasmalabs.actor.{Actor, Fsm}
import org.plasmalabs.algebras.{ClockAlgebra, Store, StoreWriter}
import org.plasmalabs.catsutils.faAsFAClockOps
import org.plasmalabs.codecs.bytes.tetra.instances.*
import org.plasmalabs.consensus.*
import org.plasmalabs.consensus.algebras.{ChainSelectionAlgebra, LocalChainAlgebra}
import org.plasmalabs.consensus.models.{BlockId, SlotData}
import org.plasmalabs.crypto.signing.Ed25519VRF
import org.plasmalabs.eventtree.ParentChildTree
import org.plasmalabs.models.p2p.*
import org.plasmalabs.networking.blockchain.BlockchainPeerClient
import org.plasmalabs.networking.fsnetwork.BlockDownloadError.BlockHeaderDownloadError
import org.plasmalabs.networking.fsnetwork.BlockDownloadError.BlockHeaderDownloadError.*
import org.plasmalabs.networking.fsnetwork.P2PShowInstances.*
import org.plasmalabs.networking.fsnetwork.PeerBlockHeaderFetcher.CompareResult.*
import org.plasmalabs.networking.fsnetwork.PeersManager.PeersManagerActor
import org.plasmalabs.networking.fsnetwork.RequestsProxy.RequestsProxyActor
import org.plasmalabs.node.models.BlockBody
import org.plasmalabs.typeclasses.implicits.*
import org.typelevel.log4cats.Logger

object PeerBlockHeaderFetcher {
  sealed trait Message

  object Message {
    case object StartActor extends Message
    case object StopActor extends Message

    /**
     * Request to download block headers from peer, downloaded headers will be sent to block checker directly
     *
     * @param blockIds headers block id to download
     */
    case class DownloadBlockHeaders(blockIds: NonEmptyChain[BlockId]) extends Message

    /**
     * Get current tip from remote peer
     */
    case object GetCurrentTip extends Message
  }

  case class State[F[_]](
    hostId:            HostId,
    hostIdString:      String,
    client:            BlockchainPeerClient[F],
    requestsProxy:     RequestsProxyActor[F],
    peersManager:      PeersManagerActor[F],
    localChain:        LocalChainAlgebra[F],
    chainSelection:    ChainSelectionAlgebra[F, BlockId, SlotData],
    peerSlotDataStore: Store[F, BlockId, SlotData],
    slotDataStore:     StoreWriter[F, BlockId, SlotData],
    bodyStore:         Store[F, BlockId, BlockBody],
    fetchingFiber:     Option[Fiber[F, Throwable, Unit]],
    clock:             ClockAlgebra[F],
    commonAncestorF:   CommonAncestorF[F],
    ed25519VRF:        Resource[F, Ed25519VRF],
    blockIdTree:       ParentChildTree[F, BlockId]
  )

  type Response[F[_]] = State[F]
  type PeerBlockHeaderFetcherActor[F[_]] = Actor[F, Message, Response[F]]

  def getFsm[F[_]: Async: Logger]: Fsm[F, State[F], Message, Response[F]] = Fsm {
    case (state, Message.StartActor)               => startActor(state)
    case (state, Message.StopActor)                => stopActor(state)
    case (state, Message.DownloadBlockHeaders(id)) => downloadHeaders(state, id)
    case (state, Message.GetCurrentTip)            => getCurrentTip(state)
  }

  def makeActor[F[_]: Async: Logger](
    hostId:          HostId,
    client:          BlockchainPeerClient[F],
    requestsProxy:   RequestsProxyActor[F],
    peersManager:    PeersManagerActor[F],
    localChain:      LocalChainAlgebra[F],
    chainSelection:  ChainSelectionAlgebra[F, BlockId, SlotData],
    slotDataStore:   Store[F, BlockId, SlotData],
    bodyStore:       Store[F, BlockId, BlockBody],
    clock:           ClockAlgebra[F],
    commonAncestorF: CommonAncestorF[F],
    ed25519VRF:      Resource[F, Ed25519VRF],
    blockIdTree:     ParentChildTree[F, BlockId]
  ): Resource[F, Actor[F, Message, Response[F]]] = {
    val peerSlotDataStore = PeerSlotDataStore.make(slotDataStore, PeerSlotDataStoreConfig(peerSlotDataStoreCacheSize))
    val initialState =
      State(
        hostId,
        show"$hostId",
        client,
        requestsProxy,
        peersManager,
        localChain,
        chainSelection,
        slotDataStore,
        peerSlotDataStore,
        bodyStore,
        None,
        clock,
        commonAncestorF,
        ed25519VRF,
        blockIdTree
      )
    val actorName = show"Header fetcher actor for peer $hostId"
    Actor.makeWithFinalize(actorName, initialState, getFsm[F], finalizer[F])
  }

  private def finalizer[F[_]: Async: Logger](state: State[F]): F[Unit] =
    stopActor(state).void

  private def startActor[F[_]: Async: Logger](state: State[F]): F[(State[F], Response[F])] =
    if (state.fetchingFiber.isEmpty) {
      for {
        _                 <- Logger[F].info(show"Start block header actor for peer ${state.hostIdString}")
        newBlockIdsStream <- state.client.remotePeerAdoptions
        fiber             <- Spawn[F].start(slotDataFetcher(state, newBlockIdsStream).compile.drain)
        newState = state.copy(fetchingFiber = Option(fiber))
      } yield (newState, newState)
    } else {
      Logger[F].info(show"Ignore starting block header actor for peer ${state.hostIdString}") >>
      (state, state).pure[F]
    }

  private def slotDataFetcher[F[_]: Async: Logger](
    state:             State[F],
    newBlockIdsStream: Stream[F, BlockId]
  ): Stream[F, Option[Long]] =
    // TODO close connection to remote peer in case of error
    newBlockIdsStream
      .evalScan(Option.empty[Long]) { (lastProcessedHeight, newBlockId) =>
        processRemoteBlockId(state, newBlockId, lastProcessedHeight)
          .handleErrorWith(
            Logger[F].error(_)(show"Fetching slot data $newBlockId from remote ${state.hostIdString} returns error") >>
            state.peersManager.sendNoWait(PeersManager.Message.NonCriticalErrorForHost(state.hostId)) >>
            Option.empty[Long].pure[F]
          )
      }

  private def processRemoteBlockId[F[_]: Async: Logger](
    state:      State[F],
    blockId:    BlockId,
    lastHeight: Option[Long]
  ): F[Option[Long]] =
    for {
      remoteSlotData <- getSlotDataFromLocalOrRemote(state)(blockId)
      remoteHeight   <- remoteSlotData.height.pure[F]
      currentHeight  <- state.localChain.head.map(_.height)
      res <- remoteSlotShallBeProcessed(remoteHeight, currentHeight, lastHeight).ifM(
        ifTrue = processRemoteSlotData(state, remoteSlotData),
        ifFalse = Logger[F].info(
          show"Skip slot $blockId from ${state.hostIdString}: rh=$remoteHeight, ch=$currentHeight, lh=$lastHeight"
        ) >>
          lastHeight.pure[F]
      )
    } yield res

  private def remoteSlotShallBeProcessed[F[_]: Async](
    remoteHeight:           Long,
    currentHeight:          Long,
    lastProcessedHeightOpt: Option[Long]
  ): F[Boolean] = {
    val lastProcessedHeight = lastProcessedHeightOpt.getOrElse(Long.MaxValue)
    (lastProcessedHeight >= remoteHeight || currentHeight >= lastProcessedHeight).pure[F]
  }

  private def processRemoteSlotData[F[_]: Async: Logger](
    state:       State[F],
    endSlotData: SlotData
  ): F[Option[Long]] =
    for {
      blockId <- endSlotData.slotId.blockId.pure[F]
      hostId  <- state.hostIdString.pure[F]
      _       <- Logger[F].info(show"Got blockId: $blockId from peer $hostId")
      toSync  <- buildSlotDataToSync(state, endSlotData)
      _       <- Logger[F].info(show"Sync $toSync from peer $hostId for $blockId")

      downloadedSlotData <- downloadSlotDataChain(state, toSync)
      _                  <- saveSlotDataChain(state, downloadedSlotData)
      _ <- Logger[F].info(show"Save tine length=${downloadedSlotData.length} from peer $hostId for $blockId")

      _            <- Async[F].cede
      chainToCheck <- buildSlotDataChainToCheck(state, toSync)
      _            <- Async[F].cede

      chainToCheckHead = chainToCheck.headOption.map(_.slotId.blockId)
      chainToCheckLast = chainToCheck.lastOption.map(_.slotId.blockId)
      _ <- Logger[F]
        .debug(
          show"CheckChain $chainToCheckHead:$chainToCheckLast len=${chainToCheck.length} from $hostId for $blockId"
        )

      compareResult <- compareSlotDataWithLocal(chainToCheck, state)
        .logDuration(show"Compare slot data end=$chainToCheckLast chain from $hostId for $blockId with local chain")
      betterChain <- compareResult match {
        case CompareResult.NoRemote =>
          Logger[F].info(show"Skip processing $blockId from peer $hostId") >>
          None.pure[F]
        case CompareResult.RemoteIsBetter(betterChain) =>
          Logger[F].debug(show"Received tip $blockId is better than current block from peer $hostId") >>
          state.requestsProxy.sendNoWait(RequestsProxy.Message.RemoteSlotData(state.hostId, betterChain)) >>
          betterChain.some.pure[F]
        case CompareResult.RemoteIsWorseByDensity =>
          Logger[F].info(show"Ignoring tip $blockId from peer $hostId because of the density rule") >>
          state.requestsProxy.sendNoWait(RequestsProxy.Message.BadKLookbackSlotData(state.hostId)) >>
          None.pure[F]
        case CompareResult.RemoteIsWorseByHeight =>
          Logger[F].info(show"Ignoring tip $blockId because other better or equal block had been adopted") >>
          None.pure[F]
      }
      lastSynced = toSync.lastOrElse(endSlotData)
      blockSourceOpt <- buildBlockSource(state, lastSynced, betterChain)
      _              <- Logger[F].debug(show"Built block source=$blockSourceOpt from peer $hostId for $blockId")
      _ <- blockSourceOpt.traverse_(s => state.peersManager.sendNoWait(PeersManager.Message.BlocksSource(s)))
    } yield lastSynced.height.some

  private def buildSlotDataToSync[F[_]: Async: Logger](
    state:       State[F],
    endSlotData: SlotData
  ): F[SlotDataToSync] =
    for {
      endBlockId <- endSlotData.slotId.blockId.pure[F]
      hostId     <- state.hostId.pure[F]
      toSync <- state.bodyStore.contains(endBlockId).flatMap {
        case true =>
          Logger[F].debug(show"Build sync data for $endBlockId: no sync is required for peer $hostId") >>
          Async[F].pure(SlotDataToSync.Empty: SlotDataToSync) // no sync is required at all
        case false =>
          state.bodyStore.contains(endSlotData.parentSlotId.blockId).flatMap {
            case true =>
              Logger[F].debug(show"Build sync data for $endBlockId: sync for $endBlockId only for peer $hostId") >>
              Async[F].pure(SlotDataToSync.One(endSlotData): SlotDataToSync)
            case false =>
              Logger[F].debug(show"Build sync data for $endBlockId: building long slot data chain for peer $hostId") >>
              buildLongSlotDataToSync(state, endSlotData)
          }
      }
    } yield toSync

  private def buildLongSlotDataToSync[F[_]: Async: Logger](
    state:   State[F],
    endSlot: SlotData
  ): F[SlotDataToSync] =
    for {
      commonBlockId    <- state.commonAncestorF(state.client, state.localChain)
      commonSlotData   <- getSlotDataFromLocalOrRemote(state)(commonBlockId)
      commonSlotHeight <- commonSlotData.height.pure[F]
      endSlotHeight    <- endSlot.height.pure[F]
      endBlockId       <- endSlot.slotId.blockId.pure[F]
      res              <-
        // could be a case if we received already abandoned chain
        if (endSlotHeight < commonSlotHeight) {
          Logger[F].info(show"Received $endBlockId from already abandoned chain from peer ${state.hostId}") >>
          SlotDataToSync.Empty.pure[F]
        } else
          buildChainSlotDataToSync(state, endSlot, commonSlotData)
    } yield res

  private def buildChainSlotDataToSync[F[_]: Async: Logger](
    state:          State[F],
    endSlot:        SlotData,
    commonSlotData: SlotData
  ): F[SlotDataToSync] =
    for {
      commonSlotHeight <- commonSlotData.height.pure[F]
      endSlotHeight    <- endSlot.height.pure[F]
      currentHeight    <- state.localChain.head.map(_.height)
      requestedHeight  <- state.chainSelection.enoughHeightToCompare(currentHeight, commonSlotHeight, endSlotHeight)
      message =
        show"For slot ${endSlot.slotId.blockId} with height $endSlotHeight from peer ${state.hostIdString} :" ++
          show" commonSlotHeight=$commonSlotHeight, requestedHeight=$requestedHeight"
      _          <- Logger[F].info(message)
      blockIdTo  <- state.client.getRemoteBlockIdAtHeight(requestedHeight).map(_.get)
      slotDataTo <- state.client.getSlotDataOrError(blockIdTo, new NoSuchElementException(blockIdTo.toString))
    } yield SlotDataToSync.Chain(commonSlotData, slotDataTo)

  // if newSlotDataOpt is defined then it will include blockId as well
  private def buildBlockSource[F[_]: Async](
    state:                State[F],
    blockSlotData:        SlotData,
    newBetterSlotDataOpt: Option[NonEmptyChain[SlotData]]
  ) =
    OptionT
      .fromOption[F](newBetterSlotDataOpt)
      .map(newSlotData => newSlotData.map(sd => (state.hostId, sd.slotId.blockId)))
      .orElseF {
        sourceOfAlreadyAdoptedBlockIsUseful(state, blockSlotData).ifM(
          ifTrue = Option(NonEmptyChain.one(state.hostId -> blockSlotData.slotId.blockId)).pure[F],
          ifFalse = Option.empty[NonEmptyChain[(HostId, BlockId)]].pure[F]
        )
      }
      .value

  // we still interesting in source if we receive current best block or short fork
  private def sourceOfAlreadyAdoptedBlockIsUseful[F[_]: Async](state: State[F], blockSlotData: SlotData): F[Boolean] =
    state.localChain.head.map(sd => sd.height == blockSlotData.height && sd.parentSlotId == blockSlotData.parentSlotId)

  private def saveSlotDataChain[F[_]: Async: Logger](
    state: State[F],
    tine:  List[SlotData]
  ): F[List[SlotData]] = {
    def adoptSlotData(slotData: SlotData) = {
      val slotBlockId = slotData.slotId.blockId
      Logger[F].info(show"Storing SlotData id=$slotBlockId from peer ${state.hostIdString} to peer local store") >>
      state.peerSlotDataStore.put(slotBlockId, slotData)
    }

    tine.traverse(adoptSlotData) >> tine.pure[F]
  }

  private def buildSlotDataChainToCheck[F[_]: Async](
    state:          State[F],
    slotDataToSync: SlotDataToSync
  ): F[List[SlotData]] =
    slotDataToSync match {
      case SlotDataToSync.Empty =>
        List.empty[SlotData].pure[F]
      case SlotDataToSync.Chain(from, to) =>
        prependOnChainUntil[F, SlotData](
          s => s.pure[F],
          state.peerSlotDataStore.getOrRaise,
          s => (s.slotId == from.slotId).pure[F]
        )(
          to.slotId.blockId
        )
      case SlotDataToSync.One(toSync) =>
        List(toSync).pure[F]
    }

  private def getSlotDataFromLocalOrRemote[F[_]: Async: Logger](state: State[F])(blockId: BlockId): F[SlotData] =
    state.peerSlotDataStore.get(blockId).flatMap {
      case Some(sd) => sd.pure[F]
      case None =>
        Logger[F].info(show"Fetching SlotData id=$blockId from peer ${state.hostIdString}") >>
        Async[F].cede >>
        state.client
          .getSlotDataOrError(blockId, new NoSuchElementException(show"$blockId"))
          // If the node is in a pre-genesis state, verify that the remote peer only notified about the genesis block.
          // It would be adversarial to send any new blocks during this time.
          .flatTap(slotData =>
            Async[F].whenA(slotData.slotId.slot >= 0)(
              Async[F].defer(
                state.clock.globalSlot.flatMap(globalSlot =>
                  Async[F]
                    .raiseWhen(globalSlot < 0)(new IllegalStateException("Peer provided new data prior to genesis"))
                )
              )
            )
          )
    }

  private def downloadSlotDataChain[F[_]: Async: Logger](
    state:          State[F],
    slotDataToSync: SlotDataToSync
  ): F[List[SlotData]] =
    slotDataToSync match {
      case SlotDataToSync.Empty           => List.empty[SlotData].pure[F]
      case SlotDataToSync.Chain(from, to) => downloadLongSlotDataChain(state, from, to)
      case SlotDataToSync.One(toSync)     => List(toSync).pure[F]
    }

  // return: recent (current) block is the last
  private def downloadLongSlotDataChain[F[_]: Async: Logger](
    state: State[F],
    from:  SlotData,
    to:    SlotData
  ): F[List[SlotData]] = {
    def iteration(acc: List[SlotData]): F[List[SlotData]] =
      state.client.getRemoteSlotDataWithParents(from.slotId.blockId, acc.head.parentSlotId.blockId).flatMap {
        case Some(chunk) =>
          val newAcc = chunk ++ acc
          state.peerSlotDataStore
            .contains(newAcc.head.slotId.blockId)
            .ifM(
              ifTrue = newAcc.dropWhileF(sd => state.peerSlotDataStore.contains(sd.slotId.blockId)),
              ifFalse = iteration(newAcc)
            )
        case None =>
          Async[F].raiseError(
            new IllegalStateException(s"Failed to download slot data chain $from : $to from host ${state.hostId}")
          )
      }

    val message = show"Slot data chain download request ${from.slotId.blockId} with height ${from.height} : " +
      show"${to.slotId.blockId} with height ${to.height} from peer ${state.hostId}"

    Logger[F].info(message) >>
    iteration(List(to))
  }

  sealed trait CompareResult

  object CompareResult {
    case class RemoteIsBetter(remote: NonEmptyChain[SlotData]) extends CompareResult
    object RemoteIsWorseByHeight extends CompareResult
    object RemoteIsWorseByDensity extends CompareResult
    object NoRemote extends CompareResult
  }

  private def compareSlotDataWithLocal[F[_]: Async](
    slotData: List[SlotData],
    state:    State[F]
  ): F[CompareResult] =
    NonEmptyChain.fromSeq(slotData) match {
      case Some(nonEmptySlotDataChain) =>
        val bestSlotData = nonEmptySlotDataChain.last
        state.localChain.isWorseThan(nonEmptySlotDataChain).flatMap {
          case true => Async[F].pure(RemoteIsBetter(nonEmptySlotDataChain))
          case false =>
            state.localChain.head.map(localHead =>
              if (localHead.height < bestSlotData.height) RemoteIsWorseByDensity else RemoteIsWorseByHeight
            )
        }
      case None => Async[F].pure(NoRemote)
    }

  private def getCurrentTip[F[_]: Async: Logger](state: State[F]): F[(State[F], Response[F])] = {
    for {
      _   <- OptionT.liftF(Logger[F].info(show"Requested current tip from peer ${state.hostIdString}"))
      tip <- OptionT(state.client.remoteCurrentTip())
      _   <- OptionT.liftF(processRemoteBlockId(state, tip, None))
      _   <- OptionT.liftF(Logger[F].info(show"Processed current tip $tip from peer ${state.hostIdString}"))
    } yield (state, state)
  }.getOrElse((state, state))
    .handleErrorWith(Logger[F].error(_)("Get tip from remote host return error") >> (state, state).pure[F])

  private def downloadHeaders[F[_]: Async: Logger](
    state:    State[F],
    blockIds: NonEmptyChain[BlockId]
  ): F[(State[F], Response[F])] =
    for {
      remoteHeaders <- getHeadersFromRemotePeer(state.client, state.hostId, blockIds)
      downloadedHeaders = remoteHeaders.flatMap { case (id, headers) => headers.toOption.map(header => (id, header)) }
      _ <- extractAndSaveSlotData(state, downloadedHeaders)
      _ <- sendHeadersToProxy(state, NonEmptyChain.fromSeq(remoteHeaders).get)
    } yield (state, state)

  private def getHeadersFromRemotePeer[F[_]: Async: Logger](
    client:   BlockchainPeerClient[F],
    hostId:   HostId,
    blockIds: NonEmptyChain[BlockId]
  ): F[List[(BlockId, Either[BlockHeaderDownloadError, UnverifiedBlockHeader])]] =
    Stream
      .foldable[F, NonEmptyChain, BlockId](blockIds)
      .evalMap(downloadHeader(client, hostId, _))
      .compile
      .toList

  private def downloadHeader[F[_]: Async: Logger](
    client:  BlockchainPeerClient[F],
    hostId:  HostId,
    blockId: BlockId
  ): F[(BlockId, Either[BlockHeaderDownloadError, UnverifiedBlockHeader])] = {
    val headerEither =
      for {
        _                              <- Logger[F].debug(show"Fetching remote header id=$blockId from peer $hostId")
        (downloadTime, headerWithNoId) <- Async[F].timed(client.getHeaderOrError(blockId, HeaderNotFoundInPeer))
        header                         <- headerWithNoId.embedId.pure[F]
        _ <- Logger[F].info(show"Fetched header $blockId: $header from $hostId for ${downloadTime.toMillis} ms")
        _ <- MonadThrow[F].raiseWhen(header.id =!= blockId)(HeaderHaveIncorrectId(blockId, header.id))
      } yield UnverifiedBlockHeader(hostId, header, downloadTime.toMillis)

    headerEither
      .map(blockHeader => Either.right[BlockHeaderDownloadError, UnverifiedBlockHeader](blockHeader))
      .handleError {
        case e: BlockHeaderDownloadError => Either.left[BlockHeaderDownloadError, UnverifiedBlockHeader](e)
        case unknownError                => Either.left(UnknownError(unknownError))
      }
      .flatTap {
        case Right(_) =>
          Logger[F].debug(show"Successfully download block header $blockId from peer $hostId")
        case Left(error) =>
          Logger[F].error(show"Failed download block $blockId from peer $hostId because of: ${error.toString}")
      }
      .map((blockId, _))
  }

  private def extractAndSaveSlotData[F[_]: Async: Logger](
    state:        State[F],
    idAndHeaders: List[(BlockId, UnverifiedBlockHeader)]
  ): F[Unit] =
    state.ed25519VRF.use { ed =>
      idAndHeaders.traverse { case (id, unverifiedBlockHeader) =>
        val parentId = unverifiedBlockHeader.blockHeader.parentHeaderId
        Logger[F].info(show"Associating child=$id to parent=$parentId") >>
        state.blockIdTree.associate(id, parentId) >>
        state.slotDataStore.put(id, unverifiedBlockHeader.blockHeader.slotData(ed))
      }.void
    }

  private def sendHeadersToProxy[F[_]](
    state:         State[F],
    headersEither: NonEmptyChain[(BlockId, Either[BlockHeaderDownloadError, UnverifiedBlockHeader])]
  ): F[Unit] = {
    val message: RequestsProxy.Message = RequestsProxy.Message.DownloadHeadersResponse(state.hostId, headersEither)
    state.requestsProxy.sendNoWait(message)
  }

  private def stopActor[F[_]: Async: Logger](state: State[F]): F[(State[F], Response[F])] =
    state.fetchingFiber
      .map { fiber =>
        val newState = state.copy(fetchingFiber = None)
        Logger[F].info(show"Stop block header fetcher fiber for peer ${state.hostIdString}") >>
        fiber.cancel >>
        (newState, newState).pure[F]
      }
      .getOrElse {
        Logger[F].info(show"Ignoring stopping block header fetcher fiber for peer ${state.hostIdString}") >>
        (state, state).pure[F]
      }

}
