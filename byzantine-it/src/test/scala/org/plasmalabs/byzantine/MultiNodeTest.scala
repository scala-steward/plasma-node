package org.plasmalabs.byzantine

import cats.data.OptionT
import cats.effect.IO
import cats.effect.implicits._
import cats.effect.kernel.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import com.spotify.docker.client.DockerClient
import fs2.Chunk
import fs2.io.file.{Files, Path, PosixPermission, PosixPermissions}
import org.plasmalabs.algebras.{NodeRpc, SynchronizationTraversalSteps}
import org.plasmalabs.blockchain._
import org.plasmalabs.byzantine.util._
import org.plasmalabs.codecs.bytes.tetra.instances._
import org.plasmalabs.codecs.bytes.typeclasses.Persistable
import org.plasmalabs.consensus.models.{BlockId, StakingAddress}
import org.plasmalabs.crypto.generation.mnemonic.Entropy
import org.plasmalabs.crypto.models.SecretKeyKesProduct
import org.plasmalabs.crypto.signing.{Ed25519, Ed25519VRF, KesProduct}
import org.plasmalabs.interpreters.NodeRpcOps._
import org.plasmalabs.models.protocol.BigBangConstants._
import org.plasmalabs.quivr.api.Prover
import org.plasmalabs.sdk.common.ContainsSignable.ContainsSignableTOps
import org.plasmalabs.sdk.common.ContainsSignable.instances._
import org.plasmalabs.sdk.constants.NetworkConstants
import org.plasmalabs.sdk.models._
import org.plasmalabs.sdk.models.box.{Attestation, Value}
import org.plasmalabs.sdk.models.transaction._
import org.plasmalabs.sdk.syntax._
import org.plasmalabs.typeclasses.implicits._
import org.typelevel.log4cats.Logger

import java.security.SecureRandom
import java.time.Instant
import scala.concurrent.duration._

class MultiNodeTest extends IntegrationSuite {
  import MultiNodeTest._

  override def munitIOTimeout: Duration = 15.minutes
  // This many nodes will be launched for this test.  All but one of the nodes will be launched immediately as
  // genesis stakers.  The final node will be launched later in the test.
  // When running on GitHub Actions, only 3 nodes can run on one machine.
  private val totalNodeCount = 3
  require(totalNodeCount >= 3)

  test("Multiple nodes launch and maintain consensus for three epochs") {
    val bigBang = Instant.now().plusSeconds(30)
    val resource =
      for {
        (dockerSupport, _dockerClient) <- DockerSupport.make[F]()
        given DockerClient = _dockerClient
        initialNodes <- List
          .tabulate(totalNodeCount - 1)(index =>
            dockerSupport.createNode(
              s"MultiNodeTest-node$index",
              "MultiNodeTest",
              TestNodeConfig(
                bigBang,
                totalNodeCount - 1,
                index,
                List("MultiNodeTest-node0"),
                serverHost = if (index == 1) s"MultiNodeTest-node$index".some else None,
                serverPort = if (index == 1) 9085.some else None
              )
            )
          )
          .sequence
        node0 = initialNodes(0)
        node1 = initialNodes(1)
        _ <- initialNodes.parTraverse(_.startContainer[F]).toResource
        _ <- initialNodes
          .parTraverse(node => node.rpcClient[F](node.config.rpcPort).use(_.waitForRpcStartUp))
          .toResource
        client <- node0.rpcClient[F](node0.config.rpcPort)
        genesisBlockId <- OptionT(client.blockIdAtHeight(BigBangHeight))
          .getOrRaise(new IllegalStateException)
          .toResource
        genesisTransaction <-
          OptionT(client.fetchBlockBody(genesisBlockId))
            .map(_.transactionIds.head)
            .flatMapF(client.fetchTransaction)
            .getOrRaise(new IllegalStateException)
            .toResource
        // Node 3 is a delayed staker.  It will register in epoch 0, but the container won't launch until epoch 2.
        tmpHostStakingDirectory <- Files.forAsync[F].tempDirectory
        _ <- Files.forAsync[F].setPosixPermissions(tmpHostStakingDirectory, PosixOtherWritePermissions).toResource
        delayedNodeName = s"MultiNodeTest-node${totalNodeCount - 1}"
        _ <- Logger[F].info(s"Registering $delayedNodeName").toResource
        delayedNodeStakingAddress <- node1
          .rpcClient[F](node1.config.rpcPort)
          // Take stake from node0 and transfer it to the delayed node
          .evalMap(registerStaker(genesisTransaction, 0, genesisBlockId)(_, tmpHostStakingDirectory))
        delayedNodeConfig = TestNodeConfig(
          bigBang,
          totalNodeCount - 1,
          -1,
          List("MultiNodeTest-node0"),
          stakingBindSourceDir = tmpHostStakingDirectory.toString.some,
          stakingAddress = delayedNodeStakingAddress.some
        )
        delayedNode <- dockerSupport.createNode(
          delayedNodeName,
          "MultiNodeTest",
          delayedNodeConfig
        )
        allNodes = initialNodes :+ delayedNode
        _ <- Logger[F].info(s"Starting $delayedNodeName").toResource
        _ <- delayedNode.startContainer[F].toResource
        _ <- Logger[F].info("Waiting for nodes to reach epoch=2.  This may take several minutes.").toResource
        thirdEpochHeads <- initialNodes
          .parTraverse(node =>
            node
              .rpcClient[F](node.config.rpcPort)
              .use(
                _.adoptedHeaders
                  .takeWhile(_.slot < (TestNodeConfig.epochSlotLength * 2))
                  // Verify that the delayed node doesn't produce any blocks in the first 2 epochs
                  .evalTap(h => IO(h.address != delayedNodeStakingAddress).assert)
                  .timeout(9.minutes)
                  .compile
                  .lastOrError
              )
          )
          .toResource
        _ <- Logger[F].info("Nodes have reached target epoch").toResource
        // The delayed node's blocks should be valid on other nodes (like node0), so search node0 for adoptions of a block produced
        // by the delayed node's staking address
        _ <- Logger[F].info("Searching for block from new staker").toResource
        _ <- client.adoptedHeaders
          .find(_.address == delayedNodeStakingAddress)
          .timeout(3.minutes)
          .compile
          .lastOrError
          .toResource
        _ <- Logger[F].info("Found block from new staker").toResource
        _ <- Logger[F].info("Verifying consensus of nodes").toResource
        heights = thirdEpochHeads.map(_.height)
        // All nodes should be at _roughly_ equal height
        _ <- IO(heights.max - heights.min <= 5).assert.toResource
        // All nodes should have a shared common ancestor near the tip of the chain
        _ <- allNodes
          .parTraverse(node =>
            node
              .rpcClient[F](node.config.rpcPort)
              .use(_.blockIdAtHeight(heights.min - 5))
          )
          .map(_.toSet.size)
          .assertEquals(1)
          .toResource
        _ <- Logger[F].info("Nodes are in consensus").toResource
      } yield ()

    resource.use_

  }

  /**
   * Generates a new staker by taking half of the stake from the given input transaction.  The keys are generated locally
   * on the host machine before being copied into the target container.  The resulting registration transaction is also
   * broadcasted using the given RPC client.
   * @param inputTransaction A transaction containing funds to split
   * @param inputIndex The UTxO index at which the funds currently exist
   * @param rpcClient the RPC client to receive the broadcasted transaction
   * @return the new staker's StakingAddress
   */
  private def registerStaker(inputTransaction: IoTransaction, inputIndex: Int, genesisBlockId: BlockId)(
    rpcClient:   NodeRpc[F, fs2.Stream[F, *]],
    localTmpDir: Path
  ): F[StakingAddress] =
    for {
      _ <- Logger[F].info("Generating new staker keys")
      kesKey <- Sync[F]
        .delay(new KesProduct().createKeyPair(SecureRandom.getInstanceStrong.generateSeed(32), (9, 9), 0))
      operatorKey <- Sync[F].delay(new Ed25519().deriveKeyPairFromEntropy(Entropy.generate(), None))
      vrfKey      <- Sync[F].delay(Ed25519VRF.precomputed().generateRandom)
      localTmpStakingDir = localTmpDir / genesisBlockId.show
      _ <- Files.forAsync[F].createDirectories(localTmpStakingDir)
      writeFile = (name: String, data: Array[Byte]) =>
        fs2.Stream.chunk(Chunk.array(data)).through(Files.forAsync[F].writeAll(localTmpStakingDir / name)).compile.drain
      _ <- Logger[F].info("Saving new staker keys to temp directory")
      _ <- Files.forAsync[F].createDirectory(localTmpStakingDir / StakingInit.KesDirectoryName)
      _ <- Files
        .forAsync[F]
        .setPosixPermissions(localTmpStakingDir / StakingInit.KesDirectoryName, PosixOtherWritePermissions)
      _ <- Files
        .forAsync[F]
        .createFile(localTmpStakingDir / StakingInit.KesDirectoryName / "0", Some(PosixOtherWritePermissions))
      _ <- Files
        .forAsync[F]
        .setPosixPermissions(localTmpStakingDir / StakingInit.KesDirectoryName / "0", PosixOtherWritePermissions)
      _ <- writeFile(
        StakingInit.KesDirectoryName + "/0",
        Persistable[SecretKeyKesProduct].persistedBytes(kesKey._1).toByteArray
      )
      _ <- writeFile(StakingInit.OperatorKeyName, operatorKey.signingKey.bytes)
      _ <- writeFile(StakingInit.VrfKeyName, vrfKey._1)
      stakerInitializer =
        StakerInitializers.Operator(
          ByteString.copyFrom(operatorKey.signingKey.bytes),
          PrivateTestnet.HeightLockOneSpendingAddress,
          ByteString.copyFrom(vrfKey._1),
          kesKey._1
        )
      spendableOutput = inputTransaction.outputs(inputIndex)
      unprovenPredicateAttestation = Attestation.Predicate(PrivateTestnet.HeightLockOneLock.getPredicate, Nil)
      unprovenInput = SpentTransactionOutput(
        TransactionOutputAddress(
          NetworkConstants.PRIVATE_NETWORK_ID,
          NetworkConstants.MAIN_LEDGER_ID,
          inputIndex,
          inputTransaction.id
        ),
        Attestation.defaultInstance.withPredicate(unprovenPredicateAttestation),
        spendableOutput.value
      )
      spendableTopl = spendableOutput.value.value.topl.get
      spendableQuantity = spendableTopl.quantity: BigInt
      changeOutput = UnspentTransactionOutput(
        PrivateTestnet.HeightLockOneSpendingAddress,
        Value.defaultInstance.withTopl(
          Value.TOPL(
            spendableQuantity - (spendableQuantity / 2),
            spendableTopl.registration
          )
        )
      )
      unprovenTransaction = stakerInitializer
        .registrationTransaction(spendableQuantity / 2)
        .withDatum(
          Datum.IoTransaction(
            Event.IoTransaction.defaultInstance.withSchedule(
              Schedule(0L, Long.MaxValue, System.currentTimeMillis())
            )
          )
        )
        .addInputs(unprovenInput)
        .addOutputs(changeOutput)

      proof <- Prover.heightProver[F].prove((), unprovenTransaction.signable)
      provenPredicateAttestation = unprovenPredicateAttestation.copy(responses = List(proof))
      transaction = unprovenTransaction.copy(
        inputs = unprovenTransaction.inputs.map(
          _.copy(attestation = Attestation(Attestation.Value.Predicate(provenPredicateAttestation)))
        )
      )
      // In parallel, broadcast the transaction and confirm it
      _ <- (
        Logger[F].info(show"Broadcasting registration transaction id=${transaction.id}") >> rpcClient
          .broadcastTransaction(transaction),
        fs2.Stream
          .force(rpcClient.synchronizationTraversal())
          .collect { case s: SynchronizationTraversalSteps.Applied =>
            s.blockId
          }
          .flatMap(id =>
            fs2.Stream.evalSeq(
              OptionT(rpcClient.fetchBlockBody(id))
                .getOrRaise(new IllegalStateException)
                .map(_.transactionIds)
            )
          )
          .find(_ == transaction.id)
          .evalTap(id => Logger[F].info(show"Confirmed registration transaction id=$id"))
          .head
          .compile
          .lastOrError
      ).parTupled
        .timeout(60.seconds)
    } yield stakerInitializer.stakingAddress
}

object MultiNodeTest {

  /**
   * The KES key needs to be modifiable by the node's container user
   */
  val PosixOtherWritePermissions: PosixPermissions =
    PosixPermissions(
      PosixPermission.OwnerRead,
      PosixPermission.OwnerWrite,
      PosixPermission.OwnerExecute,
      PosixPermission.GroupRead,
      PosixPermission.GroupWrite,
      PosixPermission.GroupExecute,
      PosixPermission.OthersRead,
      PosixPermission.OthersWrite,
      PosixPermission.OthersExecute
    )
}
