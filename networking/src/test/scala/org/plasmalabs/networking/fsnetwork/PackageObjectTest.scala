package org.plasmalabs.networking.fsnetwork

import cats.data.NonEmptyChain
import cats.effect.IO
import cats.implicits.*
import cats.{MonadThrow, Show}
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.plasmalabs.algebras.Store
import org.plasmalabs.consensus.models.{BlockId, SlotData}
import org.plasmalabs.models.ModelGenerators.GenHelper
import org.plasmalabs.models.generators.consensus.ModelGenerators
import org.plasmalabs.networking.fsnetwork.BlockCheckerTest.F
import org.plasmalabs.networking.fsnetwork.NonEmptyChainFOps
import org.plasmalabs.networking.fsnetwork.TestHelper.CallHandler3Ops
import org.plasmalabs.typeclasses.implicits.*
import org.scalacheck.Gen
import org.scalacheck.effect.PropF
import org.scalamock.munit.AsyncMockFactory
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object PackageObjectTest {
  type F[A] = IO[A]
}

class PackageObjectTest extends CatsEffectSuite with ScalaCheckEffectSuite with AsyncMockFactory {
  implicit val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

  test("getFromChainUntil shall work properly") {
    withMock {
      val blockIdSlotId =
        TestHelper
          .arbitraryLinkedSlotDataHeaderBlockNoTx(Gen.choose[Long](0, 10))
          .arbitrary
          .first
          .map(d => (d._1, d._2))
      val testDataSize = blockIdSlotId.length

      val storageData = blockIdSlotId.toList.toMap
      val storage = mock[Store[F, BlockId, SlotData]]
      (storage
        .getOrRaise(_: BlockId)(_: MonadThrow[F] @unchecked, _: Show[BlockId] @unchecked))
        .expects(*, *, *)
        .rep(testDataSize.toInt)
        .onCall { case (id: BlockId, _: MonadThrow[F] @unchecked, _: Show[BlockId] @unchecked) =>
          storageData(id).pure[F]
        }

      for {
        data <- prependOnChainUntil[F, BlockId](
          getSlotDataFromT = storage.getOrRaise,
          getT = id => id.pure[F],
          terminateOn = id => (!storageData.contains(id)).pure[F]
        )(blockIdSlotId.last._1)
        _ = assert(data == blockIdSlotId.map(_._1).toList)

      } yield ()
    }
  }

  test("getFromChainUntil shall work properly in case of monad throw error") {
    withMock {
      val blockIdSlotId =
        TestHelper
          .arbitraryLinkedSlotDataHeaderBlockNoTx(Gen.choose[Long](0, 10))
          .arbitrary
          .first
          .map(d => (d._1, d._2))

      val storageData = blockIdSlotId.toList.toMap
      val storage = mock[Store[F, BlockId, SlotData]]
      (storage
        .getOrRaise(_: BlockId)(_: MonadThrow[F] @unchecked, _: Show[BlockId] @unchecked))
        .expects(*, *, *)
        .once()
        .onCall { case (_: BlockId, _: MonadThrow[F] @unchecked, _: Show[BlockId] @unchecked) =>
          val monadThrow = implicitly[MonadThrow[F]]
          monadThrow.raiseError(new IllegalStateException())
        }

      for {
        data <- prependOnChainUntil[F, BlockId](
          getSlotDataFromT = storage.getOrRaise,
          getT = id => id.pure[F],
          terminateOn = id => (!storageData.contains(id)).pure[F]
        )(blockIdSlotId.last._1).handleError(_ => List.empty[BlockId])
        _ = assert(data == List.empty[BlockId])

      } yield ()
    }
  }

  test("dropWhile shall works properly") {
    PropF.forAllF(ModelGenerators.nonEmptyChainArbOf[Boolean].arbitrary) { (chain: NonEmptyChain[Boolean]) =>
      for {
        d <- NonEmptyChainFOps[Boolean, F](chain).dropWhileF(_.pure[F])
        _ = assert(!d.headOption.getOrElse(false))
      } yield ()
    }
  }

  test("dropWhile shall works properly for long chain") {
    val data = Seq.fill(100000)(true)
    val chain: NonEmptyChain[Boolean] = NonEmptyChain.fromSeq(data :+ false).get
    for {
      d <- NonEmptyChainFOps[Boolean, F](chain).dropWhileF(_.pure[F])
      _ = assert(d.length == 1)
      _ = assert(!d.headOption.getOrElse(false))
    } yield ()
  }
}
