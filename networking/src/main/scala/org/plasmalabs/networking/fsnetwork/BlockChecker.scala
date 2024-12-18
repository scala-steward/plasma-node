package org.plasmalabs.networking.fsnetwork

import cats.data.*
import cats.effect.{Async, Resource}
import cats.implicits.*
import fs2.Stream
import org.apache.commons.lang3.exception.ExceptionUtils
import org.plasmalabs.actor.{Actor, Fsm}
import org.plasmalabs.algebras.Store
import org.plasmalabs.blockchain.Validators
import org.plasmalabs.catsutils.faAsFAClockOps
import org.plasmalabs.codecs.bytes.tetra.instances.blockHeaderAsBlockHeaderOps
import org.plasmalabs.consensus.algebras.*
import org.plasmalabs.consensus.models.{BlockHeader, BlockId, SlotData}
import org.plasmalabs.crypto.signing.Ed25519VRF
import org.plasmalabs.ledger.implicits.*
import org.plasmalabs.ledger.interpreters.QuivrContext
import org.plasmalabs.ledger.models.{BodyProposalValidationContext, BodyValidationError, StaticBodyValidationContext}
import org.plasmalabs.models.p2p.*
import org.plasmalabs.networking.fsnetwork.BlockApplyError.BodyApplyException.BodyValidationException
import org.plasmalabs.networking.fsnetwork.BlockApplyError.HeaderApplyException.HeaderValidationException
import org.plasmalabs.networking.fsnetwork.BlockApplyError.{BodyApplyException, HeaderApplyException}
import org.plasmalabs.networking.fsnetwork.BlockChecker.Message.*
import org.plasmalabs.networking.fsnetwork.P2PShowInstances.*
import org.plasmalabs.networking.fsnetwork.RequestsProxy.RequestsProxyActor
import org.plasmalabs.node.models.*
import org.plasmalabs.typeclasses.implicits.*
import org.typelevel.log4cats.Logger

import scala.collection.Searching

object BlockChecker {
  sealed trait Message

  object Message {

    /**
     * Process new slot data from remote peer, if incoming slot data is better than from any other host then start to
     * trying to adopt data from that peer
     * @param source source of slot data, used as a hint from which peer headers shall be requested
     * @param slotData slot data to compare, slot data chain contains only new remote slot data.
     */
    case class RemoteSlotData(source: HostId, slotData: NonEmptyChain[SlotData]) extends Message

    /**
     * Check and adopt remote headers, if headers is valid then appropriate bodies will be requested
     * @param headers headers to check and adopt
     */
    case class RemoteBlockHeaders(headers: NonEmptyChain[UnverifiedBlockHeader]) extends Message

    /**
     * check and adopt block bodies, if adopted bodies is better than local chain
     * then remote bodies became new top block
     * @param blocks blocks to check
     */
    case class RemoteBlockBodies(blocks: NonEmptyChain[(BlockHeader, UnverifiedBlockBody)]) extends Message

    /**
     * Invalidate blocks because blocks are invalid by some reason, for example no block body available at any peer or
     * validation of block had been failed
     * @param blockIds invalid blockIds
     */
    case class InvalidateBlockIds(blockIds: NonEmptyChain[BlockId]) extends Message
  }

  case class State[F[_]: Async](
    requestsProxy:               RequestsProxyActor[F],
    localChain:                  LocalChainAlgebra[F],
    slotDataStore:               Store[F, BlockId, SlotData],
    headerStore:                 Store[F, BlockId, BlockHeader],
    bodyStore:                   Store[F, BlockId, BlockBody],
    chainSelection:              ChainSelectionAlgebra[F, BlockId, SlotData],
    validators:                  Validators[F],
    chunkSize:                   Int,
    ed25519VRF:                  Resource[F, Ed25519VRF],
    bestKnownRemoteSlotDataOpt:  Option[BestChain],
    bestKnownRemoteSlotDataHost: Option[HostId]
  ) {

    // Slot data fetcher which try to get slot data from best known chain, if not found then from local slot data store
    lazy val slotDataFetcher: BlockId => F[SlotData] =
      combineSlotDataSources(slotDataStore, bestKnownRemoteSlotDataOpt.fold(List.empty[SlotData])(_.slotData.toList))
  }

  type Response[F[_]] = State[F]
  type BlockCheckerActor[F[_]] = Actor[F, Message, Response[F]]

  def getFsm[F[_]: Async: Logger]: Fsm[F, State[F], Message, Response[F]] =
    Fsm {
      case (state, RemoteSlotData(hostId, slotData))    => processSlotData(state, hostId, slotData)
      case (state, RemoteBlockHeaders(blockHeaders))    => processRemoteHeaders(state, blockHeaders)
      case (state, RemoteBlockBodies(blocks))           => processRemoteBodies(state, blocks)
      case (state, InvalidateBlockIds(invalidBlockIds)) => processInvalidBlockId(state, invalidBlockIds)
    }

  def makeActor[F[_]: Async: Logger](
    requestsProxy:               RequestsProxyActor[F],
    localChain:                  LocalChainAlgebra[F],
    slotDataStore:               Store[F, BlockId, SlotData],
    headerStore:                 Store[F, BlockId, BlockHeader],
    bodyStore:                   Store[F, BlockId, BlockBody],
    validators:                  Validators[F],
    chainSelectionAlgebra:       ChainSelectionAlgebra[F, BlockId, SlotData],
    ed25519VRF:                  Resource[F, Ed25519VRF],
    p2pNetworkConfig:            P2PNetworkConfig,
    bestChain:                   Option[BestChain] = None,
    bestKnownRemoteSlotDataHost: Option[HostId] = None
  ): Resource[F, BlockCheckerActor[F]] = {
    val initialState =
      State(
        requestsProxy,
        localChain,
        slotDataStore,
        headerStore,
        bodyStore,
        chainSelectionAlgebra,
        validators,
        p2pNetworkConfig.networkProperties.chunkSize,
        ed25519VRF,
        bestChain,
        bestKnownRemoteSlotDataHost
      )
    val actorName = "Block checker actor"
    Actor.make(actorName, initialState, getFsm[F])
  }

  private def processSlotData[F[_]: Async: Logger](
    state:           State[F],
    candidateHostId: HostId,
    remoteSlotData:  NonEmptyChain[SlotData]
  ): F[(State[F], Response[F])] =
    Async[F].ifM(state.slotDataStore.contains(remoteSlotData.head.parentSlotId.blockId))(
      ifTrue = processCorrectSlotData(state, candidateHostId, remoteSlotData),
      ifFalse = Logger[F].error("Got incorrect slot data chain") >>
        invalidateBlockId(state, remoteSlotData.map(_.slotId.blockId)).map(s => (s, s))
    )

  private def processCorrectSlotData[F[_]: Async: Logger](
    state:           State[F],
    candidateHostId: HostId,
    remoteSlotData:  NonEmptyChain[SlotData]
  ): F[(State[F], Response[F])] = {
    val bestRemoteSlotData: SlotData = remoteSlotData.last
    val bestRemoteBlockId: BlockId = bestRemoteSlotData.slotId.blockId

    for {
      _ <- Logger[F].debug(show"Received slot data proposal with best block id $bestRemoteBlockId")
      newState <- remoteSlotDataBetter(state, remoteSlotData).ifM(
        ifTrue = processNewBestSlotData(state, remoteSlotData, candidateHostId),
        ifFalse = Logger[F].debug(show"Ignore weaker slot data $bestRemoteBlockId") >> state.pure[F]
      )
    } yield (newState, newState)
  }

  private def remoteSlotDataBetter[F[_]: Async: Logger](
    state:               State[F],
    remoteSlotDataChain: NonEmptyChain[SlotData]
  ): F[Boolean] =
    for {
      bestRemote <- remoteSlotDataChain.last.pure[F]
      localBest  <- state.bestKnownRemoteSlotDataOpt.fold(state.localChain.head)(_.last.pure[F])

      res <-
        // if received slot data chain could be appended to the END of current best slot data,
        // then we could skip chain comparing because
        // if chain N is better than chain M then chain N + a1 + an is better than chain M as well
        if (couldAppend(localBest, remoteSlotDataChain)) {
          Logger[F].info(show"Could be append ${bestRemote.slotId.blockId} to best chain") >>
          true.pure[F]
        } else {
          val remoteFetcher = combineSlotDataSources(state.slotDataStore, remoteSlotDataChain.toList)
          Async[F]
            .defer(state.chainSelection.compare(localBest, bestRemote, state.slotDataFetcher, remoteFetcher).map(_ < 0))
            .logDuration(show"Compare slot data chain for ${bestRemote.slotId.blockId}")
        }
    } yield res

  /**
   * Check if given remoteSlotDataChain is extension of localBestSlotData.
   * remoteSlotDataChain could be extension of localBestSlotData ONLY
   * if some element of remoteSlotDataChain have parent with id equal to id of localBestSlotData
   *
   * @param localBestSlotData slot data to append
   * @param remoteSlotDataChain chain to append
   * @return true if whole remoteSlotDataChain or it part could be appended to localBestSlotData, false otherwise
   */
  private def couldAppend(localBestSlotData: SlotData, remoteSlotDataChain: NonEmptyChain[SlotData]): Boolean = {
    // pattern for binary search, we interesting only in height, so any slot data with correct height will be ok
    val dummySlotData = localBestSlotData.copy(height = localBestSlotData.height + 1)
    val chainAsVector = remoteSlotDataChain.toNonEmptyVector.toVector
    chainAsVector.search(dummySlotData)(Ordering.by(_.height)) match {
      case Searching.Found(height)     => chainAsVector(height).parentSlotId == localBestSlotData.slotId
      case Searching.InsertionPoint(_) => false
    }
  }

  private def processNewBestSlotData[F[_]: Async: Logger](
    state:           State[F],
    remoteSlotData:  NonEmptyChain[SlotData],
    candidateHostId: HostId
  ): F[State[F]] = {
    val remoteIds: NonEmptyChain[BlockId] = remoteSlotData.map(_.slotId.blockId)
    for {
      fullSlotData <- buildFullSlotDataChain(state, remoteSlotData)
        .logDurationRes(fullSlotData => show"Build full slot data for len=${fullSlotData.size}")
      _        <- Async[F].cede
      _        <- Logger[F].debug(show"Extend slot data $remoteIds to ${fullSlotData.map(_.slotId.blockId)}")
      newState <- changeLocalSlotData(state, fullSlotData, candidateHostId)
      _        <- requestNextHeaders(newState)
    } yield newState
  }

  /**
   * Received remote slot data could be not full, because some of slot data could be already saved in slot storage,
   * thus we build full slot data
   * @param state actor state
   * @param remoteSlotData incomplete store data
   * @tparam F effect
   * @return if we return chain of slot data A0 -> A1 -> ... -> AN, then:
   *         AN == slotData.last
   *         A0 - AN == slot data for appropriate block A0-AN and header for block A0-AN is absent in header storage
   *         Header storage shall contain block header which is parent of block A0
   */
  private def buildFullSlotDataChain[F[_]: Async](
    state:          State[F],
    remoteSlotData: NonEmptyChain[SlotData]
  ): F[NonEmptyChain[SlotData]] = {
    val from = remoteSlotData.head.parentSlotId.blockId
    val missedSlotDataF = prependOnChainUntil[F, SlotData](
      getSlotDataFromT = s => s.pure[F],
      getT = state.slotDataFetcher,
      terminateOn = sd => state.bodyStore.contains(sd.slotId.blockId)
    )(from)

    missedSlotDataF.map(sd => remoteSlotData.prependChain(Chain.fromSeq(sd)))
  }

  private def changeLocalSlotData[F[_]: Async: Logger](
    state:       State[F],
    newSlotData: NonEmptyChain[SlotData],
    hostId:      HostId
  ): F[State[F]] =
    Logger[F].info(show"Update best local slot data with ${newSlotData.last.slotId.blockId}") >>
    state
      .copy(bestKnownRemoteSlotDataOpt = Option(BestChain(newSlotData)), bestKnownRemoteSlotDataHost = Option(hostId))
      .pure[F]

  private def requestNextHeaders[F[_]: Async: Logger](state: State[F]): F[Unit] = {
    for {
      bestChain     <- OptionT.fromOption[F](state.bestKnownRemoteSlotDataOpt)
      missedHeaders <- OptionT.liftF(bestChain.slotData.dropWhileF(sd => state.headerStore.contains(sd.slotId.blockId)))
      missedNeC     <- OptionT.fromOption[F](NonEmptyChain.fromChain(missedHeaders))
      blockId       <- OptionT.some[F](missedNeC.iterator.drop(state.chunkSize).nextOption().getOrElse(missedNeC.last))
      res           <- OptionT.liftF(requestHeadersUpToId(state, blockId.slotId.blockId))
    } yield res
  }.getOrElseF(().pure[F]).logDuration("Request next headers total time")

  private def requestHeadersUpToId[F[_]: Async: Logger](
    state:    State[F],
    headerId: BlockId
  ): F[Unit] =
    getFirstNMissedInStore(state.headerStore, state.slotDataFetcher, headerId, state.chunkSize)
      .logDuration(show"Find N-missing header")
      .flatTap(m => OptionT.liftF(Logger[F].info(show"Send request to get missed headers for blockIds: $m")))
      .map(RequestsProxy.Message.DownloadHeadersRequest(state.bestKnownRemoteSlotDataHost.get, _))
      .foreachF(state.requestsProxy.sendNoWait)
      .handleErrorWith(e =>
        Logger[F].error(show"Failed to request next headers due ${e.toString} ${ExceptionUtils.getStackTrace(e)}")
      )

  private def processRemoteHeaders[F[_]: Async: Logger](
    state:        State[F],
    blockHeaders: NonEmptyChain[UnverifiedBlockHeader]
  ): F[(State[F], Response[F])] =
    Logger[F].info(show"Start processing headers: $blockHeaders") >>
    OptionT(
      Stream
        .foldable(blockHeaders)
        .covaryAll[F, UnverifiedBlockHeader]
        .evalDropWhile(knownBlockHeaderPredicate(state))
        .compile
        .toList
        .map(NonEmptyChain.fromSeq)
    ).map(processNewRemoteHeaders(state, _))
      .getOrElse((state, state).pure[F])
      .flatten

  private def processNewRemoteHeaders[F[_]: Async: Logger](
    state:        State[F],
    blockHeaders: NonEmptyChain[UnverifiedBlockHeader]
  ): F[(State[F], Response[F])] = {
    def processedHeadersAndErrors(lastProcessedBodySlot: SlotData) =
      Stream
        .foldable(blockHeaders)
        .covaryAll[F, UnverifiedBlockHeader]
        .evalFilter(headerCouldBeVerified(state, lastProcessedBodySlot))
        .evalMap { unverifiedBlockHeader =>
          val blockId = unverifiedBlockHeader.blockHeader.id
          verifyOneBlockHeader(state)(unverifiedBlockHeader).logDuration(show"Verified header $blockId")
        }
        .evalTap { header =>
          state.headerStore.put(header.id, header)
        }
        .map(Right.apply[HeaderApplyException, BlockHeader])
        .handleErrorWith {
          case e: HeaderValidationException => Stream.emit(Left(e: HeaderApplyException))
          case e                            => Stream.emit(Left(HeaderApplyException.UnknownError(e)))
        }
        .evalTap(res => logHeaderValidationResult(res))
        .compile
        .toList
        .map { list =>
          val successfullyProcessed = list.collect { case Right(d) => d }
          val error = list.collectFirst { case Left(e: HeaderApplyException) => e }
          (successfullyProcessed, error)
        }

    val hostId = blockHeaders.head.source
    for {
      unknownBodies     <- getMissedBodiesId(state, blockHeaders.last.blockHeader.id).logDuration("Get missed bodies")
      lastProcessedBody <- unknownBodies.map(_.head).get.pure[F] // shall always be defined
      lastProcessedBodySlot   <- state.slotDataFetcher(lastProcessedBody)
      (appliedHeaders, error) <- processedHeadersAndErrors(lastProcessedBodySlot)
      newState                <- processHeaderValidationError(state, error)
      _ <-
        if (appliedHeaders.nonEmpty) {
          requestNextHeaders(newState) >>
          requestMissedBodies(newState, hostId, unknownBodies).void
        } else { ().pure[F] }

    } yield (newState, newState)
  }

  private def headerCouldBeVerified[F[_]: Async: Logger](state: State[F], lastProcessedBody: SlotData)(
    header: UnverifiedBlockHeader
  ): F[Boolean] =
    state.validators.header
      .couldBeValidated(header.blockHeader, lastProcessedBody)
      .warnIfSlow(show"Header-Is-Verifiable blockId=${header.blockHeader.id}")
      .ifM(
        ifTrue = Logger[F].debug(show"Header ${header.blockHeader} could be validated") >> true.pure[F],
        ifFalse = {
          val message = show"Header ${header.blockHeader} can't be validated yet, " +
            show"blocks bodies from two previous epochs shall be validated first, drop header"
          Logger[F].warn(message) >> false.pure[F]
        }
      )

  private def knownBlockHeaderPredicate[F[_]: Async: Logger](
    state: State[F]
  ): UnverifiedBlockHeader => F[Boolean] = { unverifiedBlockHeader =>
    val id = unverifiedBlockHeader.blockHeader.id
    state.headerStore.contains(id).flatTap {
      case true  => Logger[F].info(show"Ignore know block header id $id")
      case false => Logger[F].info(show"Start processing new header $id")
    }
  }

  private def verifyOneBlockHeader[F[_]: Async: Logger](
    state: State[F]
  )(unverifiedBlockHeader: UnverifiedBlockHeader): F[BlockHeader] = {
    val id = unverifiedBlockHeader.blockHeader.id
    val res = for {
      _ <- EitherT.liftF(Logger[F].debug(show"Validating remote header id=$id"))
      header <- EitherT(
        state.validators.header
          .validate(unverifiedBlockHeader.blockHeader)
          .warnIfSlow(show"Header Verification blockId=$id")
      )
    } yield header

    res.leftMap(HeaderValidationException(id, unverifiedBlockHeader.source, _)).rethrowT
  }

  private def logHeaderValidationResult[F[_]: Logger](res: Either[HeaderApplyException, BlockHeader]) =
    res match {
      case Right(header) =>
        Logger[F].info(show"Successfully process header: ${header.id}")
      case Left(HeaderValidationException(id, hostId, error)) =>
        Logger[F].error(show"Failed to apply header $id due validation error: $error from host $hostId")
      case Left(HeaderApplyException.UnknownError(error)) =>
        Logger[F].error(show"Failed to apply header due next error: ${error.toString}")
    }

  private def processHeaderValidationError[F[_]: Async: Logger](
    state: State[F],
    error: Option[HeaderApplyException]
  ): F[State[F]] =
    error
      .map {
        case HeaderValidationException(blockId, source, _) =>
          state.requestsProxy.sendNoWait(RequestsProxy.Message.InvalidateBlockId(source, blockId)) >>
          invalidateBlockId(state, NonEmptyChain.one(blockId))
        case e =>
          Logger[F].error(show"Header apply error: ${e.toString}") >>
          state.pure[F]
      }
      .getOrElse(state.pure[F])

  private def getMissedBodiesId[F[_]: Async](
    state:        State[F],
    bestKnownTip: BlockId
  ): F[Option[NonEmptyChain[BlockId]]] =
    getFirstNMissedInStore(state.bodyStore, state.slotDataFetcher, bestKnownTip, state.chunkSize).value

  private def requestMissedBodies[F[_]: Async: Logger](
    state:             State[F],
    hostId:            HostId,
    unknownBodyIdsOpt: Option[NonEmptyChain[BlockId]]
  ): F[Seq[BlockHeader]] = {
    def getKnownHeaderPrefix(ids: NonEmptyChain[BlockId]) =
      OptionT(
        fs2.Stream
          .emits(ids.toList)
          .evalMap(id => state.headerStore.get(id).map((id, _)))
          .takeWhile { case (_, headerOpt) => headerOpt.isDefined }
          .map(_._2.get.embedId)
          .compile
          .toList
          .map(NonEmptyChain.fromSeq)
      )

    val requestMissedBodiesCommand =
      for {
        unknownBodyIds         <- OptionT.fromOption[F](unknownBodyIdsOpt)
        headerForUnknownBodies <- getKnownHeaderPrefix(unknownBodyIds)
        _ <- OptionT.liftF(Logger[F].info(show"Send request to get bodies for: ${headerForUnknownBodies.map(_.id)}"))
        message = RequestsProxy.Message.DownloadBodiesRequest(hostId, headerForUnknownBodies)
        _ <- OptionT.liftF(state.requestsProxy.sendNoWait(message))
      } yield headerForUnknownBodies.toList

    requestMissedBodiesCommand
      .getOrElse(Seq.empty[BlockHeader])
      .handleErrorWith(e =>
        Logger[F].error(show"Failed to request next bodies for known headers due ${e.toString}") >>
        Seq.empty[BlockHeader].pure[F]
      )
  }

  private def processRemoteBodies[F[_]: Async: Logger](
    state:  State[F],
    blocks: NonEmptyChain[(BlockHeader, UnverifiedBlockBody)]
  ): F[(State[F], Response[F])] = {
    val processedBlocksAndError =
      Stream
        .foldable(blocks)
        .covaryAll[F, (BlockHeader, UnverifiedBlockBody)]
        .evalDropWhile(knownBlockBodyPredicate(state))
        .evalMap(headerAndBody =>
          verifyOneBlockBody(state)(headerAndBody)
            .logDuration(show"Verified body ${headerAndBody._1.id}")
        )
        .evalTap { case (id, block) => state.bodyStore.put(id, block.body) }
        .evalTap(applyOneBlockBody(state))
        .map { case (id, _) => Right(id) }
        .handleErrorWith {
          case e: BodyValidationException => Stream.emit(Left(e: BodyApplyException))
          case e                          => Stream.emit(Left(BodyApplyException.UnknownError(e)))
        }
        .evalTap(res => logBodyValidationResult(res))
        .compile
        .toList
        .map { list =>
          val successfullyProcessed = list.collect { case Right(d) => d }
          val error = list.collectFirst { case Left(e: BodyApplyException) => e }
          (successfullyProcessed, error)
        }

    val hostId = blocks.head._2.source // fallback in case if block source cache will be purged
    for {
      _                        <- Logger[F].info(show"Start processing bodies for ids: ${blocks.map(_._1.id)}")
      (appliedBlockIds, error) <- processedBlocksAndError
      stateAfterError          <- processBodyValidationError(state, error)
      newState                 <- updateState(stateAfterError, appliedBlockIds.lastOption).pure[F]
      _ <- if (appliedBlockIds.nonEmpty) requestNextBodiesOrHeader(newState, hostId) else ().pure[F]
    } yield (newState, newState)
  }

  private def knownBlockBodyPredicate[F[_]: Async: Logger](
    state: State[F]
  ): ((BlockHeader, UnverifiedBlockBody)) => F[Boolean] = { case (header, _) =>
    val id = header.id
    state.bodyStore.contains(id).flatTap {
      case true  => Logger[F].info(show"Ignore know block body id $id")
      case false => Logger[F].info(show"Start processing new block body $id")
    }
  }

  private def verifyOneBlockBody[F[_]: Async: Logger](state: State[F])(data: (BlockHeader, UnverifiedBlockBody)) = {
    val block = Block(data._1, data._2.blockBody)
    val source = data._2.source
    val id = block.header.id
    for {
      _ <- verifyBlockBody(state, id, block).leftMap(BodyValidationException(id, source, _)).rethrowT
    } yield (id, block)
  }

  private def applyOneBlockBody[F[_]: Async: Logger](state: State[F])(idAndBody: (BlockId, Block)): F[Unit] = {
    val (id, _) = idAndBody
    for {
      lastBlockSlotData <- state.slotDataStore.getOrRaise(id)
      _ <- state.localChain
        .isWorseThan(NonEmptyChain.one(lastBlockSlotData))
        .logDuration(show"Compare local chain after block apply")
        .ifM(
          ifTrue = state.localChain.adopt(Validated.Valid(lastBlockSlotData)) >>
            Logger[F].info(show"Successfully adopted block: $id"),
          ifFalse = Logger[F].info(show"Ignoring weaker (or equal) block body with id=$id")
        )
    } yield ()
  }

  private def logBodyValidationResult[F[_]: Logger](res: Either[BodyApplyException, BlockId]) =
    res match {
      case Right(id) =>
        Logger[F].info(show"Successfully process body: $id")
      case Left(BodyValidationException(id, source, errors)) =>
        Logger[F].error(show"Failed to apply body $id from host $source due errors: ${errors.mkString_(",")}")
      case Left(BodyApplyException.UnknownError(error)) =>
        Logger[F].error(show"Failed to apply body due next error: ${error.toString}")
    }

  private def processBodyValidationError[F[_]: Async: Logger](
    state: State[F],
    error: Option[BodyApplyException]
  ): F[State[F]] =
    error
      .map {
        case BodyValidationException(blockId, source, _) =>
          state.requestsProxy.sendNoWait(RequestsProxy.Message.InvalidateBlockId(source, blockId)) >>
          invalidateBlockId(state, NonEmptyChain.one(blockId))
        case e =>
          Logger[F].error(show"Body apply error: ${e.toString}") >>
          state.pure[F]
      }
      .getOrElse(state.pure[F])

  private def verifyBlockBody[F[_]: Async: Logger](
    state:   State[F],
    blockId: BlockId,
    block:   Block
  ): EitherT[F, NonEmptyChain[BodyValidationError], (BlockId, Block)] = {
    val header = block.header
    val body = block.body
    val id = header.id

    for {
      _ <- EitherT.liftF(Logger[F].debug(show"Validating syntax of body id=$blockId"))
      _ <- EitherT(
        state.validators.bodySyntax
          .validate(body)
          .map(_.toEither)
          .warnIfSlow(show"Body Syntax Validation blockId=$id")
      )
      _ <- EitherT.liftF(Logger[F].debug(show"Validating semantics of body id=$blockId"))
      validationContext = StaticBodyValidationContext(header.parentHeaderId, header.height, header.slot)
      _ <- EitherT(
        state.validators.bodySemantics
          .validate(validationContext)(body)
          .map(_.toEither)
          .warnIfSlow(show"Body Semantics Validation blockId=$id")
      )
      _ <- EitherT.liftF(Logger[F].debug(show"Validating authorization of body id=$blockId"))
      authValidation = state.validators.bodyAuthorization
        .validate(QuivrContext.forConstructedBlock(header, _))(body)
        .warnIfSlow(show"Body Authorization Validation blockId=$id")
      _ <- EitherT(authValidation.map(_.toEither.leftMap(e => e: NonEmptyChain[BodyValidationError])))
      _ <- EitherT.liftF(Logger[F].debug(show"Validating proposal of body id=$blockId"))
      proposalContext = BodyProposalValidationContext(id, header.slot)
      _ <- EitherT(
        state.validators.bodyProposalValidationAlgebra
          .validate(proposalContext)(body)
          .map(_.toEither)
          .warnIfSlow(show"Body Proposal validation blockId=$id")
      )
    } yield (blockId, block)
  }

  private def requestNextBodiesOrHeader[F[_]: Async: Logger](state: State[F], hostId: HostId): F[Unit] =
    state.bestKnownRemoteSlotDataOpt.map(_.lastId).traverse_ { bestId =>
      for {
        unknownIds   <- getMissedBodiesId(state, bestId).logDuration("Get missed bodies")
        requestedIds <- requestMissedBodies(state, hostId, unknownIds)
        _            <- if (requestedIds.isEmpty) requestNextHeaders(state) else ().pure[F]
      } yield ()
    }

  // clear bestKnownRemoteSlotData at the end of sync, so new slot data will be compared with local chain again
  private def updateState[F[_]: Async](state: State[F], newTopBlockOpt: Option[BlockId]): State[F] = {
    for {
      bestChain   <- state.bestKnownRemoteSlotDataOpt
      newTopBlock <- newTopBlockOpt
    } yield
      if (bestChain.isLastId(newTopBlock))
        state.copy(bestKnownRemoteSlotDataOpt = None, bestKnownRemoteSlotDataHost = None)
      else
        state
  }.getOrElse(state)

  private def processInvalidBlockId[F[_]: Async: Logger](
    state:          State[F],
    invalidBlockId: NonEmptyChain[BlockId]
  ): F[(State[F], Response[F])] =
    invalidateBlockId(state, invalidBlockId).map(s => (s, s))

  private def invalidateBlockId[F[_]: Async: Logger](
    state:           State[F],
    invalidBlockIds: NonEmptyChain[BlockId]
  ): F[State[F]] = {
    val newState = state.copy(bestKnownRemoteSlotDataOpt = None, bestKnownRemoteSlotDataHost = None)
    Logger[F].error(show"Clean current best chain due error in receiving/validation data from $invalidBlockIds") >>
    state.requestsProxy.sendNoWait(RequestsProxy.Message.ResetRequestsProxy) >>
    newState.pure[F]
  }

  def combineSlotDataSources[F[_]: Async](
    slotDataStore: Store[F, BlockId, SlotData],
    slotData:      List[SlotData]
  ): BlockId => F[SlotData] = {
    val sdMap = slotData.map(sd => (sd.slotId.blockId, sd)).toMap
    val mapFetcher = (id: BlockId) => sdMap.get(id).pure[F]
    val fetcher = (id: BlockId) =>
      mapFetcher(id).flatMap {
        case Some(sd) => sd.pure[F]
        case None     => slotDataStore.getOrRaise(id)
      }

    fetcher
  }
}
