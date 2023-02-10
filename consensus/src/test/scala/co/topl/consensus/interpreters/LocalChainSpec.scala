package co.topl.consensus.interpreters

import cats.Applicative
import cats.data.Validated
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits._
import co.topl.consensus.algebras.ChainSelectionAlgebra
import co.topl.models._
import co.topl.models.utility.Lengths
import co.topl.consensus.models.{BlockId, SlotData, SlotId}
import org.scalamock.scalatest.MockFactory
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import com.google.protobuf.ByteString
import co.topl.models.generators.common.ModelGenerators.genSizedStrictByteString
import co.topl.models.generators.consensus.ModelGenerators.etaGen

class LocalChainSpec
    extends AnyFlatSpec
    with ScalaCheckDrivenPropertyChecks
    with Matchers
    with MockFactory
    with EitherValues {
  behavior of "LocalChain"

  type F[A] = IO[A]

  it should "store the head of the local canonical tine" in {
    forAll(genSizedStrictByteString[Lengths.`64`.type](), etaGen) { (rho, eta) =>
      val initialHead =
        SlotData(
          SlotId.of(1, BlockId.of(ByteString.copyFrom(Bytes.fill(32)(1).toArray))),
          SlotId.of(0, BlockId.of(ByteString.copyFrom(Bytes.fill(32)(0).toArray))),
          rho.data,
          eta.data,
          0
        )

      val chainSelection: ChainSelectionAlgebra[F, SlotData] = (a, b) => a.height.compareTo(b.height).pure[F]

      val underTest = LocalChain.make[F](initialHead, chainSelection, _ => Applicative[F].unit).unsafeRunSync()

      underTest.head.unsafeRunSync() shouldBe initialHead
    }
  }

  it should "indicate when a new tine is worse than the local chain" in {
    forAll(genSizedStrictByteString[Lengths.`64`.type](), etaGen) { (rho, eta) =>
      val initialHead =
        SlotData(
          SlotId.of(1, BlockId.of(ByteString.copyFrom(Bytes.fill(32)(1).toArray))),
          SlotId.of(0, BlockId.of(ByteString.copyFrom(Bytes.fill(32)(0).toArray))),
          rho.data,
          eta.data,
          0
        )

      val chainSelection: ChainSelectionAlgebra[F, SlotData] = (a, b) => a.height.compareTo(b.height).pure[F]

      val underTest = LocalChain.make[F](initialHead, chainSelection, _ => Applicative[F].unit).unsafeRunSync()

      val newHead =
        SlotData(
          SlotId.of(2, BlockId.of(ByteString.copyFrom(Bytes.fill(32)(2).toArray))),
          SlotId.of(1, BlockId.of(ByteString.copyFrom(Bytes.fill(32)(0).toArray))),
          rho.data,
          eta.data,
          1
        )

      underTest.isWorseThan(newHead).unsafeRunSync() shouldBe true
    }
  }

  it should "adopt a new tine when instructed" in {
    forAll(genSizedStrictByteString[Lengths.`64`.type](), etaGen) { (rho, eta) =>
      val initialHead =
        SlotData(
          SlotId.of(1, BlockId.of(ByteString.copyFrom(Bytes.fill(32)(1).toArray))),
          SlotId.of(0, BlockId.of(ByteString.copyFrom(Bytes.fill(32)(0).toArray))),
          rho.data,
          eta.data,
          0
        )

      val chainSelection: ChainSelectionAlgebra[F, SlotData] = (a, b) => a.height.compareTo(b.height).pure[F]

      val underTest = LocalChain.make[F](initialHead, chainSelection, _ => Applicative[F].unit).unsafeRunSync()

      val newHead =
        SlotData(
          SlotId.of(2, BlockId.of(ByteString.copyFrom(Bytes.fill(32)(2).toArray))),
          SlotId.of(1, BlockId.of(ByteString.copyFrom(Bytes.fill(32)(0).toArray))),
          rho.data,
          eta.data,
          1
        )

      underTest.head.unsafeRunSync() shouldBe initialHead

      underTest.adopt(Validated.Valid(newHead)).unsafeRunSync()

      underTest.head.unsafeRunSync() shouldBe newHead

    }
  }
}