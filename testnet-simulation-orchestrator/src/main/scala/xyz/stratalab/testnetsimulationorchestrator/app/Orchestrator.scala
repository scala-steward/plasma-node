package xyz.stratalab.testnetsimulationorchestrator.app

import cats.Applicative
import cats.data.OptionT
import cats.effect._
import cats.effect.std.{Random, SecureRandom}
import cats.implicits._
import co.topl.brambl.models.TransactionId
import xyz.stratalab.sdk.syntax._
import xyz.stratalab.sdk.validation.{TransactionCostCalculatorInterpreter, TransactionCostConfig}
import co.topl.consensus.models.{BlockHeader, BlockId}
import co.topl.genus.services.TransactionServiceFs2Grpc
import com.typesafe.config.Config
import fs2._
import fs2.concurrent.Topic
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import xyz.stratalab.algebras.{NodeRpc, SynchronizationTraversalSteps}
import xyz.stratalab.common.application.IOBaseApp
import xyz.stratalab.grpc.NodeGrpc
import xyz.stratalab.interpreters.MultiNodeRpc
import xyz.stratalab.models.utility._
import xyz.stratalab.testnetsimulationorchestrator.algebras.DataPublisher
import xyz.stratalab.testnetsimulationorchestrator.interpreters.{GcpCsvDataPublisher, K8sSimulationController}
import xyz.stratalab.testnetsimulationorchestrator.models.{AdoptionDatum, BlockDatum, TransactionDatum}
import xyz.stratalab.transactiongenerator.interpreters.{Fs2TransactionGenerator, GenusWalletInitializer}
import xyz.stratalab.transactiongenerator.models.Wallet
import xyz.stratalab.typeclasses.implicits._

import scala.concurrent.duration._

object Orchestrator
    extends IOBaseApp[Args, ApplicationConfig](
      createArgs = args => IO.delay(Args.parserArgs.constructOrThrow(args)),
      createConfig = IOBaseApp.createTypesafeConfig(_),
      parseConfig = (_, conf) => IO.delay(ApplicationConfig.unsafe(conf))
    ) {

  private type NodeName = String

  private type NodeRpcs = Map[NodeName, NodeRpc[F, Stream[F, *]]]

  private type Publisher = DataPublisher[F, Stream[F, *]]

  implicit private val logger: Logger[F] =
    Slf4jLogger.getLoggerFromClass[F](this.getClass)

  def run(args: Args, config: Config, appConfig: ApplicationConfig): IO[Unit] =
    for {
      _ <- Logger[F].info("Launching Testnet Simulation Orchestrator")
      _ <- Logger[F].info(show"args=$args")
      _ <- Logger[F].info(show"config=$appConfig")
      _ <- runSimulation(appConfig)
    } yield ()

  /**
   * Resources required to run this simulation and cleanup
   */
  private def resources(appConfig: ApplicationConfig): Resource[F, (Publisher, NodeRpcs, Wallet)] =
    for {
      // Allocate the K8sSimulationController resource first
      k8s <- K8sSimulationController.resource[F](appConfig.simulationOrchestrator.kubernetes.namespace)
      _   <- Resource.onFinalize(Logger[F].info("Terminating testnet") >> k8s.terminate)
      csvPublisher <- GcpCsvDataPublisher.make[F](
        appConfig.simulationOrchestrator.publish.bucket,
        s"${appConfig.simulationOrchestrator.publish.filePrefix}${System.currentTimeMillis()}/"
      )
      nodeConfigs = appConfig.simulationOrchestrator.nodes
      nodeRpcClients <- nodeConfigs
        .parTraverse(n =>
          NodeGrpc.Client
            .make[F](n.host, n.port, tls = false)
            .evalTap(awaitNodeReady(n.name, _))
            .tupleLeft(n.name)
        )
        .map(_.toMap)
      genusClient <- xyz.stratalab.grpc
        .makeChannel[F](nodeConfigs.head.host, nodeConfigs.head.port, tls = false)
        .flatMap(TransactionServiceFs2Grpc.stubResource[F])
      walletInitializer <- GenusWalletInitializer.make(genusClient)
      wallet            <- walletInitializer.initialize.toResource
    } yield (csvPublisher, nodeRpcClients, wallet)

  private def awaitNodeReady(name: NodeName, client: NodeRpc[F, Stream[F, *]]) =
    Logger[F].info(show"Awaiting readiness of node=$name") >>
    Stream
      .retry(
        client
          .blockIdAtHeight(1)
          .map(_.get)
          .flatMap(client.fetchBlockHeader)
          .map(_.get.timestamp)
          .flatMap(bigBangTimestamp => Async[F].realTimeInstant.map(bigBangTimestamp - _.toEpochMilli).map(_.milli))
          .flatMap(durationUntilBigBang =>
            Applicative[F].whenA(durationUntilBigBang.toMillis > 0)(Async[F].sleep(durationUntilBigBang))
          ),
        250.milli,
        identity,
        200
      )
      .compile
      .drain >>
    Logger[F].info(show"Node node=$name is ready")

  private def runSimulation(appConfig: ApplicationConfig): F[Unit] =
    resources(appConfig).use { case (publisher, nodes, wallet) =>
      for {
        // Launch the background fiber which broadcasts Transactions to all nodes
        transactionBroadcasterFiber <- transactionBroadcaster(appConfig)(nodes, wallet)
        // Listen to the block adoptions of all nodes.
        nodeHeaderAdoptions <- fetchHeaderAdoptions(appConfig)(nodes)
        // Now that the chain has reached the target height, we can stop broadcasting the transactions
        _ <- transactionBroadcasterFiber.cancel

        // Once we have all of the adoptions, publish them
        _ <- nodeHeaderAdoptions.parTraverse { case (node, adoptions) =>
          Logger[F].info(show"Publishing adoptions for node=$node") >>
          publisher.publishAdoptions(Stream.iterable(adoptions).map(a => AdoptionDatum(a._1, a._2)), node)
        }.void

        // Each node produced a stream of adoptions.  Ideally, the streams contain duplicates, assuming the nodes
        // are in-sync.  To avoid writing duplicate blocks to the published results, deduplicate them, and assign to
        // the node that adopted first.
        blockAssignments <- assignBlocksToNodes(nodeHeaderAdoptions)

        // Similarly, deduplicate transaction-fetching assignments
        transactionAssignments <- publishBlockBodiesAndAssignTransactions(publisher, nodes)(blockAssignments)

        // Assemble the assignments into a stream, fetch the transaction, and convert into a TransactionDatum
        transactionDatumStream = Stream
          .iterable(transactionAssignments.toList)
          .parEvalMap[F, TransactionDatum](Runtime.getRuntime.availableProcessors()) { case (transactionId, node) =>
            OptionT(nodes(node).fetchTransaction(transactionId))
              .getOrRaise(new NoSuchElementException(show"Transaction not found id=$transactionId"))
              .map(TransactionDatum(_))
          }

        _ <- Logger[F].info("Fetching and publishing transactions")
        // Now publish the transaction results
        _ <- publisher.publishTransactions(transactionDatumStream)

        // Simulation complete :)
      } yield ()
    }

  /**
   * Listen to the streams of block ID adoptions from all nodes in parallel.  Simultaneously, fetch each corresponding
   * header to determine its height, which is then used to determine when to stop.
   */
  private def fetchHeaderAdoptions(appConfig: ApplicationConfig)(
    nodes: NodeRpcs
  ): F[List[(NodeName, Vector[(BlockId, Long, BlockHeader)])]] =
    nodes.toList.parTraverse { case (name, client) =>
      for {
        _          <- Logger[F].info(show"Fetching adoptions+headers from node=$name")
        baseStream <- client.synchronizationTraversal()
        stream = baseStream.collect { case SynchronizationTraversalSteps.Applied(id) => id }
        headers <- stream
          .zip(Stream.repeatEval(Async[F].defer(Async[F].realTimeInstant)))
          .evalMap { case (id, adoptionTimestamp) =>
            OptionT(client.fetchBlockHeader(id))
              .getOrRaise(new NoSuchElementException(id.show))
              .map((id, adoptionTimestamp.toEpochMilli, _))
          }
          // Stop listening once a node adopts a block at the target height.
          .takeWhile(_._3.height <= appConfig.simulationOrchestrator.scenario.targetHeight)
          .mergeHaltBoth(
            Stream.sleep[F](appConfig.simulationOrchestrator.scenario.timeout) >> Stream.exec(
              Logger[F].warn(show"node=$name timed out.  Simulation results may be incomplete for this node.")
            )
          )
          .compile
          .toVector
        _ <- Logger[F].info(show"Finished fetching adoptions+headers from node=$name")
      } yield (name -> headers)
    }

  private def assignBlocksToNodes(
    nodeBlockAdoptions: List[(NodeName, Vector[(BlockId, Long, BlockHeader)])]
  ): F[List[(NodeName, BlockId, BlockHeader)]] =
    Sync[F].delay(
      nodeBlockAdoptions
        .flatMap { case (node, adoptions) => adoptions.map { case (id, _, header) => (node, id, header) } }
        .groupBy(_._2)
        .values
        .map(_.head)
        .toList
        .sortBy(_._3.height)
    )

  private def publishBlockBodiesAndAssignTransactions(publisher: Publisher, nodes: NodeRpcs)(
    blockAssignments: List[(NodeName, BlockId, BlockHeader)]
  ): F[Map[TransactionId, NodeName]] =
    for {
      // Create a topic which is expected to contain two subscribers
      blockDatumTopic <- Topic[F, (NodeName, BlockDatum)]
      // Assemble a stream of BlockDatum by using the list of blockAssignments, fetching the body, and converting into
      // a BlockDatum
      blockDatumSourceStream = Stream
        .iterable(blockAssignments)
        .parEvalMap[F, (NodeName, BlockDatum)](Runtime.getRuntime.availableProcessors()) { case (node, id, header) =>
          OptionT(nodes(node).fetchBlockBody(id))
            .getOrRaise(new NoSuchElementException(show"Block Body not found id=$id"))
            .map(body => node -> BlockDatum(header, body))
        }
        .through(blockDatumTopic.publish)
      assignTransactionsStream =
        blockDatumTopic
          .subscribe(128)
          .fold(Map.empty[TransactionId, NodeName]) { case (assignments, (node, datum)) =>
            assignments ++ datum.body.allTransactionIds.tupleRight(node)
          }
      // Publish the block data results
      _ <- Logger[F].info("Fetching block bodies, publishing blocks, and assigning transactions (in parallel)")
      (_, _, transactionAssignments) <- (
        blockDatumSourceStream.compile.drain,
        publisher.publishBlocks(blockDatumTopic.subscribe(128).map(_._2)),
        assignTransactionsStream.compile.lastOrError
      ).parTupled
    } yield transactionAssignments

  /**
   * Creates a background-running Fiber which generates and broadcasts transactions to the nodes until the Fiber is canceled.
   */
  private def transactionBroadcaster(
    appConfig: ApplicationConfig
  )(nodes: NodeRpcs, wallet: Wallet): IO[Fiber[IO, Throwable, Unit]] =
    for {
      // Assemble a base wallet of available UTxOs
      _                            <- Logger[F].info(show"Initializing wallet")
      given Random[F] <- SecureRandom.javaSecuritySecureRandom[F]
      // Combine the Node RPCs into one interface
      client <- MultiNodeRpc.make[F, List](nodes.values.toList)
      _      <- Logger[F].info(show"Initialized wallet with spendableBoxCount=${wallet.spendableBoxes.size}")
      // Produce a stream of Transactions from the base wallet
      targetTps = appConfig.simulationOrchestrator.scenario.transactionsPerSecond
      _ <- Logger[F].info(show"Generating and broadcasting transactions at tps=$targetTps")
      transactionStream <- Fs2TransactionGenerator
        .make[F](
          wallet,
          TransactionCostCalculatorInterpreter.make[F](TransactionCostConfig()),
          Fs2TransactionGenerator.randomMetadata[F]
        )
        .flatMap(_.generateTransactions)
      // Build the stream
      runStreamF = transactionStream
        // Send 1 transaction per _this_ duration
        .metered((1000000000d / targetTps).nanos)
        // Broadcast+log the transaction
        .evalTap(transaction =>
          Logger[F].debug(show"Broadcasting transaction id=${transaction.id}") >>
          client.broadcastTransaction(transaction) >>
          Logger[F].info(show"Broadcasted transaction id=${transaction.id}")
        )
        .onError { case e =>
          Stream.eval(Logger[F].error(e)("Stream failed"))
        }
        .compile
        .drain
      // Start the fiber
      fiber <- Spawn[F].start(runStreamF)
    } yield fiber
}
