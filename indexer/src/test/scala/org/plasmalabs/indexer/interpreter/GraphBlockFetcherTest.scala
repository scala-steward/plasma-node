package org.plasmalabs.indexer.interpreter

import cats.effect.IO
import cats.implicits.*
import com.tinkerpop.blueprints.Vertex
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.plasmalabs.codecs.bytes.tetra.instances.blockHeaderAsBlockHeaderOps
import org.plasmalabs.consensus.models.BlockHeader
import org.plasmalabs.indexer.algebras.VertexFetcherAlgebra
import org.plasmalabs.indexer.model.{GE, GEs}
import org.plasmalabs.indexer.orientDb.OrientThread
import org.plasmalabs.models.generators.consensus.ModelGenerators.*
import org.plasmalabs.node.models.BlockBody
import org.scalacheck.effect.PropF
import org.scalamock.munit.AsyncMockFactory

class GraphBlockFetcherTest extends CatsEffectSuite with ScalaCheckEffectSuite with AsyncMockFactory {

  type F[A] = IO[A]

  test("On fetchCanonicalHead with throwable response, a FailureMessageWithCause should be returned") {

    withMock {

      val res = for {
        given OrientThread[F] <- OrientThread.create[F]
        graphVertexFetcher    <- mock[VertexFetcherAlgebra[F]].pure[F].toResource
        expectedTh = new IllegalStateException("boom!")
        _ = (() => graphVertexFetcher.fetchCanonicalHead())
          .expects()
          .once()
          .returning(
            (GEs
              .InternalMessageCause("GraphVertexFetcher:fetchCanonicalHead", expectedTh): GE)
              .asLeft[Option[Vertex]]
              .pure[F]
          )
        graphBlockFetcher <- GraphBlockFetcher.make[F](graphVertexFetcher)
        _ <- assertIO(
          graphBlockFetcher.fetchCanonicalHead(),
          (GEs.InternalMessageCause("GraphVertexFetcher:fetchCanonicalHead", expectedTh): GE)
            .asLeft[Option[BlockHeader]]
        ).toResource
      } yield ()

      res.use_
    }

  }

  test("On fetchCanonicalHead if an empty iterator is returned, None BlockHeader should be returned") {

    withMock {
      val res = for {
        given OrientThread[F] <- OrientThread.create[F]
        graphVertexFetcher    <- mock[VertexFetcherAlgebra[F]].pure[F].toResource
        _ = (() => graphVertexFetcher.fetchCanonicalHead())
          .expects()
          .returning(Option.empty[Vertex].asRight[GE].pure[F])
        graphBlockFetcher <- GraphBlockFetcher.make[F](graphVertexFetcher)
        _ <- assertIO(
          graphBlockFetcher.fetchCanonicalHead(),
          Option.empty[BlockHeader].asRight[GE]
        ).toResource
      } yield ()

      res.use_
    }

  }

  test("On fetchHeader with throwable response, a FailureMessageWithCause should be returned") {

    PropF.forAllF { (header: BlockHeader) =>
      withMock {

        val res = for {
          given OrientThread[F] <- OrientThread.create[F]
          graphVertexFetcher    <- mock[VertexFetcherAlgebra[F]].pure[F].toResource
          expectedTh = new IllegalStateException("boom!")
          _ = (graphVertexFetcher.fetchHeader)
            .expects(header.id)
            .once()
            .returning(
              (GEs
                .InternalMessageCause("GraphVertexFetcher:fetchHeader", expectedTh): GE)
                .asLeft[Option[Vertex]]
                .pure[F]
            )
          graphBlockFetcher <- GraphBlockFetcher.make[F](graphVertexFetcher)
          _ <- assertIO(
            graphBlockFetcher.fetchHeader(header.id),
            (GEs.InternalMessageCause("GraphVertexFetcher:fetchHeader", expectedTh): GE)
              .asLeft[Option[BlockHeader]]
          ).toResource
        } yield ()

        res.use_
      }
    }
  }

  test("On fetchHeader if an empty iterator is returned, None BlockHeader should be returned") {

    PropF.forAllF { (header: BlockHeader) =>
      withMock {
        val res = for {
          given OrientThread[F] <- OrientThread.create[F]
          graphVertexFetcher    <- mock[VertexFetcherAlgebra[F]].pure[F].toResource
          _ = (graphVertexFetcher.fetchHeader)
            .expects(header.id)
            .returning(Option.empty[Vertex].asRight[GE].pure[F])
          graphBlockFetcher <- GraphBlockFetcher.make[F](graphVertexFetcher)
          _ <- assertIO(
            graphBlockFetcher.fetchHeader(header.id),
            Option.empty[BlockHeader].asRight[GE]
          ).toResource
        } yield ()

        res.use_
      }
    }
  }

  test("On fetchHeaderByHeight with throwable response, a MessageWithCause should be returned") {

    PropF.forAllF { (header: BlockHeader) =>
      withMock {
        val res = for {
          given OrientThread[F] <- OrientThread.create[F]
          graphVertexFetcher    <- mock[VertexFetcherAlgebra[F]].pure[F].toResource
          expectedTh = new IllegalStateException("boom!")
          _ = (graphVertexFetcher.fetchHeaderByHeight)
            .expects(header.height)
            .once()
            .returning(
              (GEs
                .InternalMessageCause("GraphVertexFetcher:fetchHeaderByHeight", expectedTh): GE)
                .asLeft[Option[Vertex]]
                .pure[F]
            )
          graphBlockFetcher <- GraphBlockFetcher.make[F](graphVertexFetcher)
          _ <- assertIO(
            graphBlockFetcher.fetchHeaderByHeight(header.height),
            (GEs.InternalMessageCause("GraphVertexFetcher:fetchHeaderByHeight", expectedTh): GE)
              .asLeft[Option[BlockHeader]]
          ).toResource
        } yield ()

        res.use_
      }
    }
  }

  test("On fetchHeaderByHeight, if an empty iterator is returned, None BlockHeader should be returned") {
    PropF.forAllF { (header: BlockHeader) =>
      withMock {
        val res = for {
          given OrientThread[F] <- OrientThread.create[F]
          graphVertexFetcher    <- mock[VertexFetcherAlgebra[F]].pure[F].toResource
          _ = (graphVertexFetcher.fetchHeaderByHeight)
            .expects(header.height)
            .once()
            .returning(Option.empty[Vertex].asRight[GE].pure[F])
          graphBlockFetcher <- GraphBlockFetcher.make[F](graphVertexFetcher)
          _ <- assertIO(
            graphBlockFetcher.fetchHeaderByHeight(header.height),
            Option.empty[BlockHeader].asRight[GE]
          ).toResource
        } yield ()

        res.use_
      }
    }
  }

  test("On fetchBody, if empty is returned fetching blockHeader Vertex, None BlockBody should be returned") {
    PropF.forAllF { (header: BlockHeader) =>
      withMock {
        val res = for {
          given OrientThread[F] <- OrientThread.create[F]
          graphVertexFetcher    <- mock[VertexFetcherAlgebra[F]].pure[F].toResource
          _ = (graphVertexFetcher.fetchHeader)
            .expects(header.id)
            .once()
            .returning(Option.empty[Vertex].asRight[GE].pure[F])
          graphBlockFetcher <- GraphBlockFetcher.make[F](graphVertexFetcher)
          _ <- assertIO(
            graphBlockFetcher.fetchBody(header.id),
            Option.empty[BlockBody].asRight[GE]
          ).toResource
        } yield ()

        res.use_
      }
    }
  }

  test("On fetchBody, if header Vertex exits, but body vertex does not, None BlockBody should be returned") {
    PropF.forAllF { (header: BlockHeader) =>
      withMock {
        val res = for {
          given OrientThread[F] <- OrientThread.create[F]
          graphVertexFetcher    <- mock[VertexFetcherAlgebra[F]].pure[F].toResource
          headerVertex          <- mock[Vertex].pure[F].toResource

          _ = (graphVertexFetcher.fetchHeader)
            .expects(header.id)
            .once()
            .returning(headerVertex.some.asRight[GE].pure[F])

          _ = (graphVertexFetcher.fetchBody)
            .expects(headerVertex)
            .once()
            .returning(Option.empty[Vertex].asRight[GE].pure[F])

          graphBlockFetcher <- GraphBlockFetcher.make[F](graphVertexFetcher)

          _ <- assertIO(
            graphBlockFetcher.fetchBody(header.id),
            Option.empty[BlockBody].asRight[GE]
          ).toResource
        } yield ()

        res.use_
      }
    }
  }

}
