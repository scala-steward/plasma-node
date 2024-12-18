package org.plasmalabs.byzantine

import com.spotify.docker.client.DockerClient
import org.plasmalabs.byzantine.util._
import org.plasmalabs.interpreters.NodeRpcOps._
import org.typelevel.log4cats.Logger

import scala.concurrent.duration._

class SanityCheckNodeTest extends IntegrationSuite {

  test("A single node is successfully started, id of the genesis block is available through RPC") {
    val resource =
      for {
        (dockerSupport, _dockerClient) <- DockerSupport.make[F]()
        given DockerClient = _dockerClient
        node1 <- dockerSupport.createNode(
          "SingleNodeTest-node1",
          "SingleNodeTest",
          TestNodeConfig(indexerEnabled = true)
        )
        _              <- node1.startContainer[F].toResource
        node1Client    <- node1.rpcClient[F](node1.config.rpcPort, tls = false)
        indexer1Client <- node1.rpcIndexerClient[F](node1.config.rpcPort, tls = false)
        _              <- node1Client.waitForRpcStartUp.toResource
        _              <- indexer1Client.waitForRpcStartUp.toResource
        _              <- Logger[F].info("Fetching genesis block Node Grpc Client").toResource
        _              <- node1Client.blockIdAtHeight(1).map(_.nonEmpty).assert.toResource
        _              <- Logger[F].info("Fetching genesis block Indexer Grpc Client").toResource
        _              <- indexer1Client.blockIdAtHeight(1).map(_.block.header.height).assertEquals(1L).toResource
        // Restart the container to verify that it is able to reload from disk
        _ <- node1.restartContainer[F].toResource
        _ <- node1Client.waitForRpcStartUp.toResource
        _ <- fs2.Stream
          .force(node1Client.synchronizationTraversal())
          .drop(1)
          .head
          .compile
          .lastOrError
          // The node likely restarts in the middle of an operational period, and the linear keys become unavailable
          // until the next operational period (which may take a minute or two)
          .timeout(3.minute)
          .toResource
        _ <- Logger[F].info("Success").toResource
      } yield ()

    resource.use_
  }
}
