package xyz.stratalab.blockchain

import cats.effect.{Async, Resource}
import co.topl.proto.node.NodeConfig
import fs2.Stream
import xyz.stratalab.algebras.ProtocolConfigurationAlgebra

/**
 * Emits a stream of node protocol configs.
 */
object ProtocolConfiguration {

  def make[F[_]: Async](
    nodeProtocolConfigs: Seq[NodeConfig]
  ): Resource[F, ProtocolConfigurationAlgebra[F, Stream[F, *]]] =
    Resource.pure(
      new ProtocolConfigurationAlgebra[F, Stream[F, *]] {

        override def fetchNodeConfig: F[Stream[F, NodeConfig]] =
          Async[F].delay(
            Stream.emits[F, NodeConfig](nodeProtocolConfigs)
          )
      }
    )
}