package co.topl.genus.interpreters.mongo

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import cats.Applicative
import cats.implicits._
import co.topl.genus.algebras.MongoSubscription
import co.topl.genus.services.transactions_query.TransactionSorting
import co.topl.genus.typeclasses.MongoFilter
import co.topl.genus.typeclasses.implicits._
import org.mongodb.scala.{Document, MongoCollection}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

object MongoSubscriptionImpl {

  def make[F[_]: Applicative](
    batchSize:  Int,
    collection: MongoCollection[Document]
  )(implicit
    materializer:     Materializer,
    executionContext: ExecutionContext
  ): MongoSubscription[F] =
    new MongoSubscription[F] {

      override def create[Filter: MongoFilter](
        filter: Filter
      ): F[Source[Document, NotUsed]] =
        Source
          .unfoldAsync(0)(index =>
            Source
              .fromPublisher(
                collection
                  .find(filter.toBsonFilter)
                  .sort(
                    TransactionSorting(
                      TransactionSorting.SortBy.Height(TransactionSorting.Height())
                    ).toBsonSorting
                  )
                  .skip(index)
                  .limit(batchSize)
              )
              .runWith(Sink.seq[Document])
              // increment the current index in the stream of documents for the next batch
              .map(documents => (index + documents.length -> documents.toList).some)
          )
          // TODO make these values configurable
          .throttle(1, 5.seconds)
          .mapConcat(values => values)
          .pure[F]
    }
}
