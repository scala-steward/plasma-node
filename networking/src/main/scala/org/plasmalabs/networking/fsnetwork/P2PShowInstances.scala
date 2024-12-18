package org.plasmalabs.networking.fsnetwork

import cats.Show
import cats.implicits.showInterpolator
import org.plasmalabs.codecs.bytes.tetra.instances.blockHeaderAsBlockHeaderOps
import org.plasmalabs.config.ApplicationConfig.Node.NetworkProperties
import org.plasmalabs.consensus.models.{BlockHeaderToBodyValidationFailure, BlockHeaderValidationFailure}
import org.plasmalabs.models.p2p.*
import org.plasmalabs.models.utility.byteStringToByteVector
import org.plasmalabs.networking.fsnetwork.NetworkQualityError.*
import org.plasmalabs.networking.fsnetwork.PeersManager.Message.PingPongMessagePing
import org.plasmalabs.networking.p2p.ConnectedPeer
import org.plasmalabs.node.models.*
import org.plasmalabs.typeclasses.implicits.*

import java.time.*

trait P2PShowInstances {
  implicit val showHostId: Show[HostId] = id => show"${id.id.toBase58.take(8)}..."

  implicit val showRemotePeer: Show[RemotePeer] = rp => show"RemotePeer(id=${rp.peerId} address=${rp.address})"

  implicit val showConnectedPeer: Show[ConnectedPeer] = cp => cp.toString

  implicit val showBlockHeaderValidationFailure: Show[BlockHeaderValidationFailure] =
    Show.fromToString

  implicit val showHeaderToBodyError: Show[BlockHeaderToBodyValidationFailure] =
    Show.fromToString

  implicit val showKnownHostReq: Show[CurrentKnownHostsReq] = req => s"CurrentKnownHostsReq(maxCount=${req.maxCount})"

  implicit val showPingMessage: Show[PingMessage] = message => s"PingMessage with len ${message.ping.length}"

  implicit val showNoPongMessage: Show[NetworkQualityError] = {
    case _: NoPongMessage.type        => "Failed to receive pong message"
    case _: IncorrectPongMessage.type => "Receive incorrect pong message"
  }

  implicit val showPongMessage: Show[PingPongMessagePing] = {
    case PingPongMessagePing(host, Right(delay))       => show"Received pong delay $delay from host $host"
    case PingPongMessagePing(host, Left(networkError)) => show"$networkError from host $host"
  }

  implicit val showPeerState: Show[PeerState] = {
    case PeerState.Banned  => "BANNED"
    case PeerState.Cold    => "COLD"
    case PeerState.Warm    => "WARM"
    case PeerState.Hot     => "HOT"
    case PeerState.Unknown => "UNKNOWN"
  }

  implicit val showUnverifiedHeader: Show[UnverifiedBlockHeader] = header =>
    show"UnverifiedBlockHeader(id=${header.blockHeader.id}, " +
    show"source=${header.source}, downloadTime=${header.downloadTimeMs} ms)"

  implicit val showNetworkProperties: Show[NetworkProperties] = prop => plainClassAsString(prop)

  implicit val showNetworkConfig: Show[P2PNetworkConfig] = config =>
    show"Network properties=${config.networkProperties};" ++
    s" Slot duration=${config.slotDuration.toMillis} ms;" ++
    s" BlockNoveltyInitialValue=${config.blockNoveltyInitialValue};" ++
    s" BlockNoveltyReputationStep=${config.blockNoveltyReputationStep};" ++
    s" BlockNoveltyDecoy=${config.blockNoveltyDecoy};" ++
    s" PerformanceReputationIdealValue=${config.performanceReputationIdealValue};" ++
    s" PerformanceReputationMaxDelay=${config.performanceReputationMaxDelay} ms;" ++
    s" RemotePeerNoveltyInSlots=${config.remotePeerNoveltyInSlots};" ++
    s" MaxPeerNovelty=${config.maxPeerNovelty};" ++
    s" WarmHostsUpdateInterval=${config.peersUpdateInterval.toMillis} ms;" ++
    s" AggressiveP2PRequestInterval=${config.aggressiveP2PRequestInterval.toMillis} ms;"

  implicit def showPeer[F[_]]: Show[Peer[F]] = { (peer: Peer[F]) =>
    val connectionAddress = peer.connectedAddress.fold("absent")(ra => show"$ra")
    val serverAddress = peer.asServer.fold("absent")(rp => show"$rp")
    "<<< Peer" +
    show" Connected address=$connectionAddress;" +
    show" Server address=$serverAddress;" +
    s" State=${peer.state.toString};" +
    s" Actor=${if (peer.actorOpt.isDefined) "present" else "absent"};" +
    s" Remote peer=${if (peer.remoteNetworkLevel) "active" else "no active"};" +
    f" Rep: block=${peer.blockRep}%.2f, perf=${peer.perfRep}%.2f, new=${peer.newRep}, mean=${peer.reputation}%.2f;" +
    s" With total ${peer.closedTimestamps.size} closes with" +
    s" last close ${peer.closedTimestamps.lastOption.map(l => Instant.ofEpochMilli(l).toString).getOrElse("none")};" +
    " >>>"
  }

  implicit val remotePeerShow: Show[KnownRemotePeer] = { (remotePeer: KnownRemotePeer) =>
    s"Remote peer: ${remotePeer.address}"
  }

  implicit val KnownHostShow: Show[KnownHost] = { (knownHost: KnownHost) =>
    show"Known host(id=${knownHost.id}, host=${knownHost.host}, port=${knownHost.port})"
  }

  // TODO work with multi level classes
  private def plainClassAsString(objToString: Product): String =
    (objToString.productElementNames zip objToString.productIterator)
      .map { case (paramName, param) => s"$paramName=$param, " }
      .foldLeft("")(_ + _)

  implicit val SlotDataToSyncShow: Show[SlotDataToSync] = {
    case SlotDataToSync.Empty           => "slot data sync empty"
    case SlotDataToSync.Chain(from, to) => show"slot data sync chain ${from.slotId.blockId}:${to.slotId.blockId}"
    case SlotDataToSync.One(toSync)     => show"slot data sync one ${toSync.slotId.blockId}"
  }
}

object P2PShowInstances extends P2PShowInstances
