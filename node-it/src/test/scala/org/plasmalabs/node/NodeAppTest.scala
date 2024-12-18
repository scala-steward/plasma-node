package org.plasmalabs.node

import cats.data.OptionT
import cats.effect._
import cats.effect.implicits._
import cats.effect.std.{Random, SecureRandom}
import cats.implicits._
import fs2.io.file.{Files, Path}
import fs2.{io => _, _}
import io.grpc.Metadata
import munit._
import org.plasmalabs.codecs.bytes.tetra.instances.blockHeaderAsBlockHeaderOps
import org.plasmalabs.consensus.models.BlockId
import org.plasmalabs.grpc.NodeGrpc
import org.plasmalabs.indexer.services._
import org.plasmalabs.interpreters.NodeRpcOps.clientAsNodeRpcApi
import org.plasmalabs.node.Util._
import org.plasmalabs.sdk.syntax._
import org.plasmalabs.transactiongenerator.interpreters.Fs2TransactionGenerator
import org.plasmalabs.typeclasses.implicits._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration._

class NodeAppTest extends CatsEffectSuite {

  override val munitIOTimeout: Duration = 3.minutes

  test("Two block-producing nodes that maintain consensus") {
    // Allow the nodes to produce/adopt blocks until reaching this height
    val targetProductionHeight = 10
    // All of the nodes should agree on the same block at this height
    val targetConsensusHeight = 8
    def configNodeA(dataDir: Path, stakingDir: Path, genesisBlockId: BlockId, genesisSourcePath: String) =
      s"""
         |node:
         |  data:
         |    directory: $dataDir
         |  staking:
         |    directory: $stakingDir
         |  p2p:
         |    bind-port: 9150
         |    public-port: 9150
         |  rpc:
         |    bind-port: 9151
         |  big-bang:
         |    type: public
         |    genesis-id: ${genesisBlockId.show}
         |    source-path: $genesisSourcePath
         |  mempool:
         |    protection:
         |      enabled: false
         |indexer:
         |  enable: true
         |""".stripMargin
    def configNodeB(dataDir: Path, stakingDir: Path, genesisBlockId: BlockId, genesisSourcePath: String) =
      s"""
         |node:
         |  data:
         |    directory: $dataDir
         |  staking:
         |    directory: $stakingDir
         |  p2p:
         |    bind-port: 9152
         |    public-port: 9152
         |    known-peers: 127.0.0.2:9150
         |  rpc:
         |    bind-port: 9153
         |  ethereum-json-rpc:
         |    bind-port: 8546
         |  big-bang:
         |    type: public
         |    genesis-id: ${genesisBlockId.show}
         |    source-path: $genesisSourcePath
         |  mempool:
         |    protection:
         |      enabled: false
         |indexer:
         |  enable: false
         |""".stripMargin

    val resource =
      for {
        testnetConfig     <- createTestnetConfig.toResource
        genesisServerPort <- serveGenesisBlock(testnetConfig.genesis)
        genesisSourcePath = s"http://localhost:$genesisServerPort/${testnetConfig.genesis.header.id.show}"
        configALocation <- (
          Files.forAsync[F].tempDirectory,
          Files
            .forAsync[F]
            .tempDirectory
            .evalTap(saveStaker(_)(testnetConfig.stakers(0)._1, testnetConfig.stakers(0)._2))
        ).tupled
          .map { case (dataDir, stakingDir) =>
            configNodeA(dataDir, stakingDir, testnetConfig.genesis.header.id, genesisSourcePath)
          }
          .flatMap(saveLocalConfig(_, "nodeA"))
          .map(_.toString)
        configBLocation <- (
          Files.forAsync[F].tempDirectory,
          Files
            .forAsync[F]
            .tempDirectory
            .evalTap(saveStaker(_)(testnetConfig.stakers(1)._1, testnetConfig.stakers(1)._2))
        ).tupled
          .map { case (dataDir, stakingDir) =>
            configNodeB(dataDir, stakingDir, testnetConfig.genesis.header.id, genesisSourcePath)
          }
          .flatMap(serveConfig(_, "nodeB.yaml"))
        // Run the nodes in separate fibers, but use the fibers' outcomes as an error signal to
        // the test by racing the computation
        _ <- (launch(configALocation), launch(configBLocation))
          .parMapN(_.race(_).map(_.merge).flatMap(_.embedNever))
          .flatMap(nodeCompletion =>
            nodeCompletion.toResource.race(for {
              rpcClientA <- NodeGrpc.Client.make[F]("127.0.0.2", 9151, tls = false)
              rpcClientB <- NodeGrpc.Client.make[F]("localhost", 9153, tls = false)
              rpcClients = List(rpcClientA, rpcClientB)
              given Logger[F]   <- Slf4jLogger.fromName[F]("NodeAppTest").toResource
              _                 <- rpcClients.parTraverse(_.waitForRpcStartUp).toResource
              indexerChannelA   <- org.plasmalabs.grpc.makeChannel[F]("localhost", 9151, tls = false)
              indexerTxServiceA <- TransactionServiceFs2Grpc.stubResource[F](indexerChannelA)
              wallet            <- makeWallet(indexerTxServiceA)
              _                 <- IO(wallet.spendableBoxes.nonEmpty).assert.toResource
              given Random[F]   <- SecureRandom.javaSecuritySecureRandom[F].toResource
              // Construct two competing graphs of transactions.
              // Graph 1 has higher fees and should be included in the chain
              transactionGenerator1 <-
                Fs2TransactionGenerator
                  .make[F](wallet, _ => 1000L, Fs2TransactionGenerator.emptyMetadata[F])
                  .toResource
              transactionGraph1 <- Stream
                .force(transactionGenerator1.generateTransactions())
                .take(10)
                .compile
                .toList
                .toResource
              _ <- IO(transactionGraph1.length == 10).assert.toResource
              // Graph 2 has lower fees, so the Block Packer should never choose them
              transactionGenerator2 <-
                Fs2TransactionGenerator
                  .make[F](wallet, _ => 10L, Fs2TransactionGenerator.randomMetadata[F])
                  .toResource
              transactionGraph2 <- Stream
                .force(transactionGenerator2.generateTransactions())
                .take(10)
                .compile
                .toList
                .toResource
              _ <- IO(transactionGraph2.length == 10).assert.toResource
              _ <- rpcClients.parTraverse(fetchUntilHeight(_, 2)).toResource
              // Broadcast _all_ of the good transactions to the nodes randomly
              _ <-
                Stream
                  .repeatEval(Random[F].elementOf(rpcClients))
                  .zip(Stream.evalSeq(Random[F].shuffleList(transactionGraph1)))
                  .evalMap { case (client, tx) => client.broadcastTransaction(tx) }
                  .compile
                  .drain
                  .toResource
              // Verify that the good transactions were confirmed by both nodes
              _ <- rpcClients
                .parTraverse(client =>
                  Async[F].timeout(confirmTransactions(client)(transactionGraph1.map(_.id).toSet), 60.seconds)
                )
                .toResource
              // Submit the bad transactions
              _ <- Stream
                .repeatEval(Random[F].elementOf(rpcClients))
                .zip(Stream.evalSeq(Random[F].shuffleList(transactionGraph2)))
                .evalMap { case (client, tx) => client.broadcastTransaction(tx) }
                .compile
                .drain
                .toResource
              // Verify that the nodes are still making blocks properly
              _ <- rpcClients.parTraverse(fetchUntilHeight(_, targetProductionHeight)).toResource
              // Verify that the "bad" transactions did not make it onto the chain
              _ <- rpcClients
                .parTraverse(verifyNotConfirmed(_)(transactionGraph2.map(_.id).toSet))
                .toResource
              spentTxos <- indexerTxServiceA
                .getTxosByLockAddress(
                  QueryByLockAddressRequest(wallet.propositions.keys.head, None, TxoState.SPENT),
                  new Metadata()
                )
                .map(_.txos)
                .toResource
              _ <- IO(
                spentTxos.exists(_.spender.exists(_.inputAddress.id == transactionGraph1.head.id))
              ).assert.toResource
              // Now check consensus
              idsAtTargetHeight <- rpcClients
                .traverse(client =>
                  OptionT(client.blockIdAtHeight(targetConsensusHeight)).getOrRaise(new IllegalStateException)
                )
                .toResource
              _ <- IO(idsAtTargetHeight.toSet.size == 1).assert.toResource
            } yield ())
          )
      } yield ()
    resource.use_
  }
}
