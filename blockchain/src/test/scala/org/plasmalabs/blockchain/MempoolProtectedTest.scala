package org.plasmalabs.blockchain

import cats.data.Validated
import cats.effect.IO
import cats.implicits.*
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.plasmalabs.algebras.Stats.Implicits.*
import org.plasmalabs.codecs.bytes.tetra.instances.*
import org.plasmalabs.config.ApplicationConfig.Node.MempoolProtection
import org.plasmalabs.ledger.algebras.*
import org.plasmalabs.ledger.models.{IoTransactionEx, MempoolGraph, RewardQuantities, *}
import org.plasmalabs.models.ModelGenerators.*
import org.plasmalabs.models.generators.consensus.*
import org.plasmalabs.models.generators.consensus.ModelGenerators.arbitraryBlockId
import org.plasmalabs.networking.fsnetwork.TestHelper.arbitraryIoTransaction
import org.plasmalabs.quivr.runtime.DynamicContext
import org.plasmalabs.sdk.constants.NetworkConstants
import org.plasmalabs.sdk.models.transaction.IoTransaction
import org.plasmalabs.sdk.models.{Datum, TransactionId, TransactionOutputAddress}
import org.plasmalabs.sdk.syntax.*
import org.plasmalabs.sdk.validation.TransactionAuthorizationError
import org.plasmalabs.sdk.validation.algebras.{TransactionAuthorizationVerifier, TransactionCostCalculator}
import org.scalacheck.Arbitrary
import org.scalamock.munit.AsyncMockFactory
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.annotation.tailrec

class MempoolProtectedTest extends CatsEffectSuite with ScalaCheckEffectSuite with AsyncMockFactory {
  type F[A] = IO[A]

  implicit val logger: Logger[F] = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

  private val dummyRewardCalc: TransactionRewardCalculatorAlgebra = (_: IoTransaction) => RewardQuantities()
  private val dummyCostCalc: TransactionCostCalculator = (tx: IoTransaction) => tx.inputs.size

  @tailrec
  private def arbitraryTxWithInput: IoTransaction = {
    val tx = Arbitrary(arbitraryIoTransaction.arbitrary.map(_.embedId)).arbitrary.first
    if (tx.inputs.nonEmpty) tx
    else arbitraryTxWithInput
  }

  def makeTransaction: (IoTransaction, TransactionId => F[IoTransaction]) = {
    val tx = arbitraryTxWithInput
    val fetcher = (_: TransactionId) => tx.pure[F]
    (tx, fetcher)
  }

  def semanticAndAuthCheckOk(
    tx: IoTransaction
  ): (TransactionSemanticValidationAlgebra[F], TransactionAuthorizationVerifier[F]) = {
    val semanticValidationAlgebra = mock[TransactionSemanticValidationAlgebra[F]]
    val transactionAuthorizationVerifier = mock[TransactionAuthorizationVerifier[F]]

    (semanticValidationAlgebra
      .validate(_: TransactionValidationContext)(_: IoTransaction))
      .expects(*, tx)
      .anyNumberOfTimes()
      .returns(
        Validated
          .validNec[TransactionSemanticError, IoTransaction](tx)
          .pure[F]
      )

    (transactionAuthorizationVerifier
      .validate(_: DynamicContext[F, String, Datum])(_: IoTransaction))
      .expects(*, tx)
      .anyNumberOfTimes()
      .returns(
        Either
          .right[TransactionAuthorizationError, IoTransaction](tx)
          .pure[F]
      )

    (semanticValidationAlgebra, transactionAuthorizationVerifier)
  }

  test("Protected memory pool shall just pass request to underlying if protection is disabled") {
    withMock {
      val semanticValidationAlgebra = mock[TransactionSemanticValidationAlgebra[F]]
      val transactionAuthorizationVerifier = mock[TransactionAuthorizationVerifier[F]]

      val transactionRewardCalculator = dummyRewardCalc
      val txCostCalculator = dummyCostCalc

      val boxIdToHeight = mock[TransactionOutputAddress => F[Option[Long]]]
      val config = MempoolProtection(enabled = false)
      val currentBlockHeader = Arbitrary(ModelGenerators.headerGen()).arbitrary.first
      val currentBlockHeaderF = currentBlockHeader.pure[F]

      val underlying = mock[MempoolAlgebra[F]]
      val readBlockId = arbitraryBlockId.arbitrary.first
      val readMempoolGraph = MempoolGraph(Map.empty, Map.empty, Map.empty, dummyRewardCalc, dummyCostCalc)
      (underlying.read).expects(readBlockId).once().returns(readMempoolGraph.pure[F])

      val removeTx = arbitraryTxWithInput
      (underlying.remove).expects(removeTx.id).returns(().pure[F])

      val containsBlockId = arbitraryBlockId.arbitrary.first
      val containsTx = arbitraryTxWithInput
      (underlying.contains).expects(containsBlockId, containsTx.id).returns(true.pure[F])

      val (addedTx, fetchTransaction) = makeTransaction
      val addMempoolGraph = MempoolGraph(Map.empty, Map.empty, Map.empty, dummyRewardCalc, dummyCostCalc)
      (underlying.read).expects(currentBlockHeader.id).once().returns(addMempoolGraph.pure[F])
      (underlying.add).expects(addedTx.id).once().returns(true.pure[F])

      val res =
        for {
          mempool <- MempoolProtected.make[F](
            underlying,
            semanticValidationAlgebra,
            transactionAuthorizationVerifier,
            currentBlockHeaderF,
            fetchTransaction,
            transactionRewardCalculator,
            txCostCalculator,
            boxIdToHeight,
            config
          )

          _ <- mempool.read(readBlockId).toResource
          _ <- mempool.remove(removeTx.id).toResource
          _ <- mempool.contains(containsBlockId, containsTx.id).assertEquals(true).toResource
          _ <- mempool.add(addedTx.id).assertEquals(true).toResource

        } yield ()

      res.use_
    }
  }

  test("Protected memory pool shall not work if threshold doesn't hit / reject invalid semantic tx") {
    withMock {
      val semanticValidationAlgebra = mock[TransactionSemanticValidationAlgebra[F]]
      val transactionAuthorizationVerifier = mock[TransactionAuthorizationVerifier[F]]

      val transactionRewardCalculator = dummyRewardCalc

      val boxIdToHeight = mock[TransactionOutputAddress => F[Option[Long]]]
      val maxMempoolSize = 10000
      val config =
        MempoolProtection(enabled = true, protectionEnabledThresholdPercent = 10, maxMempoolSize = maxMempoolSize)
      val currentBlockHeader = Arbitrary(ModelGenerators.headerGen()).arbitrary.first
      val currentBlockHeaderF = currentBlockHeader.pure[F]

      val underlying = mock[MempoolAlgebra[F]]
      val (addedTx, fetchTransaction) = makeTransaction
      val costMock = mock[TransactionCostCalculator]

      val txInMempool = arbitraryTxWithInput
      val txExInMempool = IoTransactionEx(txInMempool, RewardQuantities(), 500L)
      val mempoolGraph =
        MempoolGraph(Map(txInMempool.id -> txExInMempool), Map.empty, Map.empty, dummyRewardCalc, dummyCostCalc)

      // first tx, no check because we didn't hit threshold
      (underlying.read).expects(currentBlockHeader.id).once().returns(mempoolGraph.pure[F])
      (costMock.costOf).expects(addedTx).returns(499L)
      (underlying.add).expects(addedTx.id).once().returns(true.pure[F])

      // second tx, we hit threshold, semantic check will fail
      (underlying.read).expects(currentBlockHeader.id).once().returns(mempoolGraph.pure[F])
      (costMock.costOf).expects(addedTx).returns(500L)
      (semanticValidationAlgebra
        .validate(_: TransactionValidationContext)(_: IoTransaction))
        .expects(*, addedTx)
        .once()
        .returns(
          Validated
            .invalidNec[TransactionSemanticError, IoTransaction](
              TransactionSemanticErrors.UnspendableBox(
                TransactionOutputAddress(
                  NetworkConstants.PRIVATE_NETWORK_ID,
                  NetworkConstants.MAIN_LEDGER_ID,
                  0,
                  addedTx.id
                )
              )
            )
            .pure[F]
        )

      val res =
        for {
          mempool <- MempoolProtected.make[F](
            underlying,
            semanticValidationAlgebra,
            transactionAuthorizationVerifier,
            currentBlockHeaderF,
            fetchTransaction,
            transactionRewardCalculator,
            costMock,
            boxIdToHeight,
            config
          )

          _ <- mempool.add(addedTx.id).assertEquals(true).toResource
          _ <- mempool.add(addedTx.id).assertEquals(false).toResource

        } yield ()

      res.use_
    }
  }

  test("Do not add tx if semantic check is failed / Semantic check shall use different contexts") {
    withMock {
      val semanticValidationAlgebra = mock[TransactionSemanticValidationAlgebra[F]]

      val transactionAuthorizationVerifier = mock[TransactionAuthorizationVerifier[F]]

      val transactionRewardCalculator = dummyRewardCalc

      val boxIdToHeight = mock[TransactionOutputAddress => F[Option[Long]]]
      val maxMempoolSize = 10000
      val config =
        MempoolProtection(
          enabled = true,
          protectionEnabledThresholdPercent = 10,
          useMempoolForSemanticThresholdPercent = 15,
          maxMempoolSize = maxMempoolSize
        )
      val currentBlockHeader = Arbitrary(ModelGenerators.headerGen()).arbitrary.first
      val currentBlockHeaderF = currentBlockHeader.pure[F]

      val underlying = mock[MempoolAlgebra[F]]
      val (addedTx, fetchTransaction) = makeTransaction
      val costMock = mock[TransactionCostCalculator]

      val txInMempool = arbitraryTxWithInput
      val txExInMempool = IoTransactionEx(txInMempool, RewardQuantities(), 500L)
      val mempoolGraph =
        MempoolGraph(Map(txInMempool.id -> txExInMempool), Map.empty, Map.empty, dummyRewardCalc, dummyCostCalc)

      // Consider tx already in memorypool because did not hit useMempoolForSemanticThreshold threshold
      (underlying.read).expects(currentBlockHeader.id).once().returns(mempoolGraph.pure[F])
      (costMock.costOf).expects(addedTx).returns(500L)

      val useTxInMempoolContext = StaticTransactionValidationContext(
        currentBlockHeader.id,
        Seq(txInMempool),
        currentBlockHeader.height,
        currentBlockHeader.slot
      )

      (semanticValidationAlgebra
        .validate(_: TransactionValidationContext)(_: IoTransaction))
        .expects(useTxInMempoolContext, addedTx)
        .once()
        .returns(
          Validated
            .invalidNec[TransactionSemanticError, IoTransaction](
              TransactionSemanticErrors.UnspendableBox(
                TransactionOutputAddress(
                  NetworkConstants.PRIVATE_NETWORK_ID,
                  NetworkConstants.MAIN_LEDGER_ID,
                  0,
                  addedTx.id
                )
              )
            )
            .pure[F]
        )

      // Do not consider tx already in memorypool because hit useMempoolForSemanticThreshold threshold
      (underlying.read).expects(currentBlockHeader.id).once().returns(mempoolGraph.pure[F])
      (costMock.costOf).expects(addedTx).returns(1000L)

      val doNotUseTxInMempoolContext = StaticTransactionValidationContext(
        currentBlockHeader.id,
        Seq.empty,
        currentBlockHeader.height,
        currentBlockHeader.slot
      )

      (semanticValidationAlgebra
        .validate(_: TransactionValidationContext)(_: IoTransaction))
        .expects(doNotUseTxInMempoolContext, addedTx)
        .once()
        .returns(
          Validated
            .invalidNec[TransactionSemanticError, IoTransaction](
              TransactionSemanticErrors.UnspendableBox(
                TransactionOutputAddress(
                  NetworkConstants.PRIVATE_NETWORK_ID,
                  NetworkConstants.MAIN_LEDGER_ID,
                  0,
                  addedTx.id
                )
              )
            )
            .pure[F]
        )

      val res =
        for {
          mempool <- MempoolProtected.make[F](
            underlying,
            semanticValidationAlgebra,
            transactionAuthorizationVerifier,
            currentBlockHeaderF,
            fetchTransaction,
            transactionRewardCalculator,
            costMock,
            boxIdToHeight,
            config
          )

          _ <- mempool.add(addedTx.id).assertEquals(false).toResource
          _ <- mempool.add(addedTx.id).assertEquals(false).toResource

        } yield ()

      res.use_
    }
  }

  test("Do not add tx if auth check is failed") {
    withMock {
      val semanticValidationAlgebra = mock[TransactionSemanticValidationAlgebra[F]]

      val transactionAuthorizationVerifier = mock[TransactionAuthorizationVerifier[F]]

      val transactionRewardCalculator = dummyRewardCalc

      val boxIdToHeight = mock[TransactionOutputAddress => F[Option[Long]]]
      val maxMempoolSize = 10000
      val config =
        MempoolProtection(
          enabled = true,
          protectionEnabledThresholdPercent = 10,
          maxMempoolSize = maxMempoolSize
        )
      val currentBlockHeader = Arbitrary(ModelGenerators.headerGen()).arbitrary.first
      val currentBlockHeaderF = currentBlockHeader.pure[F]

      val underlying = mock[MempoolAlgebra[F]]
      val (addedTx, fetchTransaction) = makeTransaction
      val costMock = mock[TransactionCostCalculator]

      val txInMempool = arbitraryTxWithInput
      val txExInMempool = IoTransactionEx(txInMempool, RewardQuantities(), 500L)
      val mempoolGraph =
        MempoolGraph(Map(txInMempool.id -> txExInMempool), Map.empty, Map.empty, dummyRewardCalc, dummyCostCalc)

      (underlying.read).expects(currentBlockHeader.id).once().returns(mempoolGraph.pure[F])
      (costMock.costOf).expects(addedTx).returns(1000L)

      (semanticValidationAlgebra
        .validate(_: TransactionValidationContext)(_: IoTransaction))
        .expects(*, addedTx)
        .once()
        .returns(
          Validated
            .validNec[TransactionSemanticError, IoTransaction](addedTx)
            .pure[F]
        )

      (transactionAuthorizationVerifier
        .validate(_: DynamicContext[F, String, Datum])(_: IoTransaction))
        .expects(*, addedTx)
        .once()
        .returns(
          Either
            .left[TransactionAuthorizationError, IoTransaction](TransactionAuthorizationError.AuthorizationFailed())
            .pure[F]
        )

      val res =
        for {
          mempool <- MempoolProtected.make[F](
            underlying,
            semanticValidationAlgebra,
            transactionAuthorizationVerifier,
            currentBlockHeaderF,
            fetchTransaction,
            transactionRewardCalculator,
            costMock,
            boxIdToHeight,
            config
          )

          _ <- mempool.add(addedTx.id).assertEquals(false).toResource
        } yield ()

      res.use_
    }
  }

  test("Skip fee and age checks if threshold is not hit") {
    withMock {
      val transactionRewardCalculator = dummyRewardCalc
      val boxIdToHeight = mock[TransactionOutputAddress => F[Option[Long]]]
      val maxMempoolSize = 10000
      val config =
        MempoolProtection(
          enabled = true,
          protectionEnabledThresholdPercent = 10,
          maxMempoolSize = maxMempoolSize,
          feeFilterThresholdPercent = 100,
          ageFilterThresholdPercent = 100
        )
      val currentBlockHeader = Arbitrary(ModelGenerators.headerGen()).arbitrary.first
      val currentBlockHeaderF = currentBlockHeader.pure[F]
      val underlying = mock[MempoolAlgebra[F]]
      val (addedTx, fetchTransaction) = makeTransaction
      val (semanticValidationAlgebra, transactionAuthorizationVerifier) = semanticAndAuthCheckOk(addedTx)
      val costMock = mock[TransactionCostCalculator]

      val txInMempool = arbitraryTxWithInput
      val txExInMempool = IoTransactionEx(txInMempool, RewardQuantities(), 500L)
      val mempoolGraph =
        MempoolGraph(Map(txInMempool.id -> txExInMempool), Map.empty, Map.empty, dummyRewardCalc, dummyCostCalc)

      (underlying.read).expects(currentBlockHeader.id).once().returns(mempoolGraph.pure[F])
      (costMock.costOf).expects(addedTx).returns(1000L)
      (underlying.add).expects(addedTx.id).once().returns(false.pure[F])

      val res =
        for {
          mempool <- MempoolProtected.make[F](
            underlying,
            semanticValidationAlgebra,
            transactionAuthorizationVerifier,
            currentBlockHeaderF,
            fetchTransaction,
            transactionRewardCalculator,
            costMock,
            boxIdToHeight,
            config
          )

          _ <- mempool.add(addedTx.id).assertEquals(false).toResource
        } yield ()

      res.use_
    }
  }

  def checkFee(feeThreshold: Double): F[Unit] = {
    val boxIdToHeight = (_: TransactionOutputAddress) => 0L.some.pure[F]
    val maxMempoolSize = 10240
    val config =
      MempoolProtection(
        enabled = true,
        protectionEnabledThresholdPercent = 10,
        maxMempoolSize = maxMempoolSize,
        feeFilterThresholdPercent = feeThreshold,
        ageFilterThresholdPercent = 100,
        maxOldBoxAge = 0
      )
    val currentBlockHeader = Arbitrary(ModelGenerators.headerGen()).arbitrary.first
    val currentBlockHeaderF = currentBlockHeader.pure[F]
    val underlying = mock[MempoolAlgebra[F]]
    val (addedTx, fetchTransaction) = makeTransaction
    val (semanticValidationAlgebra, transactionAuthorizationVerifier) = semanticAndAuthCheckOk(addedTx)
    val costMock = mock[TransactionCostCalculator]
    val rewardMock = mock[TransactionRewardCalculatorAlgebra]

    val txInMempool = arbitraryTxWithInput
    val sizeInMempool = Math.round(maxMempoolSize * feeThreshold / 100)
    val feePerKbInMempool = 100.0
    val feeInMempool = feePerKbInMempool * sizeInMempool / 1024
    val freeMempoolSizePercent = (maxMempoolSize - sizeInMempool).toDouble / maxMempoolSize.toDouble
    val txExInMempool = IoTransactionEx(txInMempool, RewardQuantities(topl = feeInMempool.toLong), sizeInMempool)
    val mempoolGraph =
      MempoolGraph(Map(txInMempool.id -> txExInMempool), Map.empty, Map.empty, dummyRewardCalc, dummyCostCalc)

    (underlying.read)
      .expects(currentBlockHeader.id)
      .once()
      .returns(mempoolGraph.pure[F])

    val feeBelowTxSize = 1024
    val feeBelow = (feePerKbInMempool / freeMempoolSizePercent - feePerKbInMempool - 1) * feeBelowTxSize / 1024

    (costMock.costOf)
      .expects(addedTx)
      .returns(feeBelowTxSize)

    (rewardMock.rewardsOf)
      .expects(addedTx)
      .returns(RewardQuantities(topl = Math.floor(feeBelow).toLong))

    (underlying.read).expects(currentBlockHeader.id).once().returns(mempoolGraph.pure[F])
    val feeUpperTxSize = 1024
    val feeUpper = (feePerKbInMempool / freeMempoolSizePercent - feePerKbInMempool + 1) * feeBelowTxSize / 1024

    (costMock.costOf)
      .expects(addedTx)
      .returns(feeUpperTxSize)

    (rewardMock.rewardsOf)
      .expects(addedTx)
      .returns(RewardQuantities(topl = Math.ceil(feeUpper).toLong))

    (underlying.add)
      .expects(addedTx.id)
      .once()
      .returns(true.pure[F])

    val res =
      for {
        mempool <- MempoolProtected.make[F](
          underlying,
          semanticValidationAlgebra,
          transactionAuthorizationVerifier,
          currentBlockHeaderF,
          fetchTransaction,
          rewardMock,
          costMock,
          boxIdToHeight,
          config
        )

        _ <- mempool.add(addedTx.id).assertEquals(false).toResource
        _ <- mempool.add(addedTx.id).assertEquals(true).toResource
      } yield ()

    res.use_
  }

  test("Check fee filter 0.1")(withMock(checkFee(feeThreshold = 0.1)))
  test("Check fee filter 10")(withMock(checkFee(feeThreshold = 10)))
  test("Check fee filter 25")(withMock(checkFee(feeThreshold = 25)))
  test("Check fee filter 50")(withMock(checkFee(feeThreshold = 50)))
  test("Check fee filter 60")(withMock(checkFee(feeThreshold = 60)))
  test("Check fee filter 75")(withMock(checkFee(feeThreshold = 75)))
  test("Check fee filter 85")(withMock(checkFee(feeThreshold = 85)))
  test("Check fee filter 90")(withMock(checkFee(feeThreshold = 90)))
  test("Check fee filter 95")(withMock(checkFee(feeThreshold = 95)))
  test("Check fee filter 99")(withMock(checkFee(feeThreshold = 99)))

  def checkAge(ageThresholdPercent: Double, freeAgePoolPercent: Double): F[Unit] = {
    val boxIdToHeight = mock[TransactionOutputAddress => F[Option[Long]]]
    val maxMempoolSize = 10240
    val maxOldBoxAge = 1000
    val config =
      MempoolProtection(
        enabled = true,
        protectionEnabledThresholdPercent = 1,
        maxMempoolSize = maxMempoolSize,
        feeFilterThresholdPercent = 100,
        ageFilterThresholdPercent = ageThresholdPercent,
        maxOldBoxAge = maxOldBoxAge
      )

    val currentHeight = 1000
    val currentBlockHeader = Arbitrary(ModelGenerators.headerGen()).arbitrary.first.copy(height = currentHeight)
    val currentBlockHeaderF = currentBlockHeader.pure[F]
    val underlying = mock[MempoolAlgebra[F]]
    val (addedTx, fetchTransaction) = makeTransaction
    val addedTxInputsSize = addedTx.inputs.size
    val (semanticValidationAlgebra, transactionAuthorizationVerifier) = semanticAndAuthCheckOk(addedTx)
    val rewardMock = mock[TransactionRewardCalculatorAlgebra]
    val costMock = mock[TransactionCostCalculator]
    (costMock.costOf).expects(addedTx).anyNumberOfTimes().returns(1)
    (rewardMock.rewardsOf)
      .expects(addedTx)
      .anyNumberOfTimes()
      .returns(RewardQuantities(topl = BigInt(Math.round(Double.MaxValue))))

    val txInMempool = arbitraryTxWithInput
    val agePoolSizePercent = 100 - ageThresholdPercent
    val occupiedAgePoolPercent = 100 - freeAgePoolPercent
    val occupiedPercent = ageThresholdPercent + (agePoolSizePercent * occupiedAgePoolPercent / 100)
    val feeInMempool = 1
    val occupiedMempoolSize = Math.ceil(maxMempoolSize * occupiedPercent / 100.0).toLong
    val txExInMempool =
      IoTransactionEx(txInMempool, RewardQuantities(topl = feeInMempool.toLong), occupiedMempoolSize)
    val mempoolGraph =
      MempoolGraph(Map(txInMempool.id -> txExInMempool), Map.empty, Map.empty, dummyRewardCalc, dummyCostCalc)

    (underlying.read).expects(currentBlockHeader.id).once().returns(mempoolGraph.pure[F])
    val heightBellow =
      (currentHeight - (maxOldBoxAge - Math.ceil((maxOldBoxAge * freeAgePoolPercent) / 100)) + 1).toLong
    (boxIdToHeight.apply).expects(*).repeat(addedTxInputsSize).returns(heightBellow.some.pure[F])

    (underlying.read).expects(currentBlockHeader.id).once().returns(mempoolGraph.pure[F])
    val heightUpper =
      (currentHeight - (maxOldBoxAge - Math.floor((maxOldBoxAge * freeAgePoolPercent) / 100)) - 1).toLong
    (boxIdToHeight.apply).expects(*).repeat(addedTxInputsSize).returns(heightUpper.some.pure[F])
    (underlying.add).expects(addedTx.id).once().returns(true.pure[F])

    val res =
      for {
        mempool <- MempoolProtected.make[F](
          underlying,
          semanticValidationAlgebra,
          transactionAuthorizationVerifier,
          currentBlockHeaderF,
          fetchTransaction,
          rewardMock,
          costMock,
          boxIdToHeight,
          config
        )

        _ <- mempool.add(addedTx.id).assertEquals(false).toResource
        _ <- mempool.add(addedTx.id).assertEquals(true).toResource
      } yield ()

    res.use_
  }

  test("Check age filter 0.1 *")(withMock(checkAge(ageThresholdPercent = 0.1, freeAgePoolPercent = 1)))
  test("Check age filter 0.1 *")(withMock(checkAge(ageThresholdPercent = 0.1, freeAgePoolPercent = 10)))
  test("Check age filter 0.1 *")(withMock(checkAge(ageThresholdPercent = 0.1, freeAgePoolPercent = 50)))
  test("Check age filter 0.1 *")(withMock(checkAge(ageThresholdPercent = 0.1, freeAgePoolPercent = 75)))
  test("Check age filter 0.1 *")(withMock(checkAge(ageThresholdPercent = 0.1, freeAgePoolPercent = 90)))
  test("Check age filter 0.1 *")(withMock(checkAge(ageThresholdPercent = 0.1, freeAgePoolPercent = 95)))
  test("Check age filter 0.1 *")(withMock(checkAge(ageThresholdPercent = 0.1, freeAgePoolPercent = 99)))

  test("Check age filter 10 *")(withMock(checkAge(ageThresholdPercent = 10, freeAgePoolPercent = 1)))
  test("Check age filter 10 *")(withMock(checkAge(ageThresholdPercent = 10, freeAgePoolPercent = 10)))
  test("Check age filter 10 *")(withMock(checkAge(ageThresholdPercent = 10, freeAgePoolPercent = 50)))
  test("Check age filter 10 *")(withMock(checkAge(ageThresholdPercent = 10, freeAgePoolPercent = 75)))
  test("Check age filter 10 *")(withMock(checkAge(ageThresholdPercent = 10, freeAgePoolPercent = 90)))
  test("Check age filter 10 *")(withMock(checkAge(ageThresholdPercent = 10, freeAgePoolPercent = 95)))
  test("Check age filter 10 *")(withMock(checkAge(ageThresholdPercent = 10, freeAgePoolPercent = 99)))

  test("Check age filter 25 *")(withMock(checkAge(ageThresholdPercent = 25, freeAgePoolPercent = 1)))
  test("Check age filter 25 *")(withMock(checkAge(ageThresholdPercent = 25, freeAgePoolPercent = 10)))
  test("Check age filter 25 *")(withMock(checkAge(ageThresholdPercent = 25, freeAgePoolPercent = 50)))
  test("Check age filter 25 *")(withMock(checkAge(ageThresholdPercent = 25, freeAgePoolPercent = 75)))
  test("Check age filter 25 *")(withMock(checkAge(ageThresholdPercent = 25, freeAgePoolPercent = 90)))
  test("Check age filter 25 *")(withMock(checkAge(ageThresholdPercent = 25, freeAgePoolPercent = 95)))
  test("Check age filter 25 *")(withMock(checkAge(ageThresholdPercent = 25, freeAgePoolPercent = 99)))

  test("Check age filter 60 *")(withMock(checkAge(ageThresholdPercent = 60, freeAgePoolPercent = 1)))
  test("Check age filter 60 *")(withMock(checkAge(ageThresholdPercent = 60, freeAgePoolPercent = 10)))
  test("Check age filter 60 *")(withMock(checkAge(ageThresholdPercent = 60, freeAgePoolPercent = 50)))
  test("Check age filter 60 *")(withMock(checkAge(ageThresholdPercent = 60, freeAgePoolPercent = 75)))
  test("Check age filter 60 *")(withMock(checkAge(ageThresholdPercent = 60, freeAgePoolPercent = 90)))
  test("Check age filter 60 *")(withMock(checkAge(ageThresholdPercent = 60, freeAgePoolPercent = 95)))
  test("Check age filter 60 *")(withMock(checkAge(ageThresholdPercent = 60, freeAgePoolPercent = 99)))

  test("Check age filter 85 *")(withMock(checkAge(ageThresholdPercent = 85, freeAgePoolPercent = 1)))
  test("Check age filter 85 *")(withMock(checkAge(ageThresholdPercent = 85, freeAgePoolPercent = 10)))
  test("Check age filter 85 *")(withMock(checkAge(ageThresholdPercent = 85, freeAgePoolPercent = 50)))
  test("Check age filter 85 *")(withMock(checkAge(ageThresholdPercent = 85, freeAgePoolPercent = 75)))
  test("Check age filter 85 *")(withMock(checkAge(ageThresholdPercent = 85, freeAgePoolPercent = 90)))
  test("Check age filter 85 *")(withMock(checkAge(ageThresholdPercent = 85, freeAgePoolPercent = 95)))
  test("Check age filter 85 *")(withMock(checkAge(ageThresholdPercent = 85, freeAgePoolPercent = 99)))

  test("Check age filter 90 *")(withMock(checkAge(ageThresholdPercent = 90, freeAgePoolPercent = 1)))
  test("Check age filter 90 *")(withMock(checkAge(ageThresholdPercent = 90, freeAgePoolPercent = 10)))
  test("Check age filter 90 *")(withMock(checkAge(ageThresholdPercent = 90, freeAgePoolPercent = 50)))
  test("Check age filter 90 *")(withMock(checkAge(ageThresholdPercent = 90, freeAgePoolPercent = 75)))
  test("Check age filter 90 *")(withMock(checkAge(ageThresholdPercent = 90, freeAgePoolPercent = 90)))
  test("Check age filter 90 *")(withMock(checkAge(ageThresholdPercent = 90, freeAgePoolPercent = 95)))
  test("Check age filter 90 *")(withMock(checkAge(ageThresholdPercent = 90, freeAgePoolPercent = 99)))

}
