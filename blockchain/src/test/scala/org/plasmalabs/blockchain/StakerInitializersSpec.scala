package org.plasmalabs.blockchain

import cats.effect.IO
import cats.effect.kernel.Async
import cats.implicits.*
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.plasmalabs.blockchain.PrivateTestnet.{DefaultTotalStake, GroupPolicyEth, SeriesPolicyEth}
import org.plasmalabs.config.ApplicationConfig
import org.plasmalabs.models.utility.Ratio
import org.plasmalabs.numerics.implicits.*
import org.plasmalabs.quivr.models.Int128
import org.plasmalabs.sdk.models.box.Value
import org.plasmalabs.sdk.syntax.*
import org.scalamock.munit.AsyncMockFactory

import scala.concurrent.duration.FiniteDuration

class StakerInitializersSpec extends CatsEffectSuite with ScalaCheckEffectSuite with AsyncMockFactory {

  type F[A] = IO[A]

  test("Big bang outputs for PrivateTestnet, currentTime, 1 staker") {
    for {
      timestamp <- System.currentTimeMillis().pure[F]
      operator  <- PrivateTestnet.stakerInitializers(timestamp, 1).pure[F]
      bigBangOutputs <- Async[F].delay(
        operator.head.registrationTransaction(Ratio(DefaultTotalStake, 1: BigInt).round).outputs
      )

      _ <- assertIO(bigBangOutputs.size.pure[F], 1)
      _ <- assertIOBoolean(bigBangOutputs.forall(_.address == operator.head.lockAddress).pure[F])
      _ <- assertIOBoolean(
        bigBangOutputs
          .map(_.value)
          .contains(
            Value.defaultInstance
              .withTopl(Value.TOPL(Ratio(DefaultTotalStake, 1: BigInt).round, operator.head.registration.some))
          )
          .pure[F]
      )
    } yield ()
  }

  test("Big bang outputs for PrivateTestnet, currentTime + 5 seg, 1 staker") {
    for {
      timestamp <- (System.currentTimeMillis() + 5000).pure[F]
      operator  <- PrivateTestnet.stakerInitializers(timestamp, 1).pure[F]
      bigBangOutputs <- Async[F].delay(
        operator.head.registrationTransaction(Ratio(DefaultTotalStake, 1: BigInt).round).outputs
      )

      _ <- assertIO(bigBangOutputs.size.pure[F], 1)
      _ <- assertIOBoolean(bigBangOutputs.forall(_.address == operator.head.lockAddress).pure[F])
      _ <- assertIOBoolean(
        bigBangOutputs
          .map(_.value)
          .contains(
            Value.defaultInstance
              .withTopl(Value.TOPL(Ratio(DefaultTotalStake, 1: BigInt).round, operator.head.registration.some))
          )
          .pure[F]
      )
    } yield ()
  }

  test("Big bang outputs for PrivateTestnet, currentTime, 10 stakers") {
    for {
      timestamp <- System.currentTimeMillis().pure[F]
      operator  <- PrivateTestnet.stakerInitializers(timestamp, 2).pure[F]
      _ <- operator
        .zip(LazyList.from(1))
        .traverse { case (operator, index) =>
          val bigBangOutputs = operator.registrationTransaction(Ratio(DefaultTotalStake, index: BigInt).round).outputs

          assertIO(bigBangOutputs.size.pure[F], 1) &>
          assertIOBoolean(bigBangOutputs.forall(_.address == operator.lockAddress).pure[F]) &>
          assertIOBoolean(
            bigBangOutputs
              .map(_.value)
              .contains(
                Value.defaultInstance
                  .withTopl(Value.TOPL(Ratio(DefaultTotalStake, index: BigInt).round, operator.registration.some))
              )
              .pure[F]
          )

        }
        .void
    } yield ()
  }

  test("Big bang config appended outputs for PrivateTestnet, currentTime, 1 staker") {
    for {
      timestamp <- System.currentTimeMillis().pure[F]
      operator  <- PrivateTestnet.stakerInitializers(timestamp, 1).pure[F]
      bigBangConfig <- PrivateTestnet
        .config(
          timestamp,
          operator,
          Some(List(1: BigInt)),
          PrivateTestnet.DefaultProtocolVersion,
          // https://plasma-labs.atlassian.net/browse/BN-1667
          // config module should implement and expose generators for config case classes.
          protocol = ApplicationConfig.Node
            .Protocol("", Ratio.One, 1, 1, Ratio.One, Ratio.One, 1L, 1L, FiniteDuration(1, "s"), 1L, 1L, 1, 1, None)
        )
        .pure[F]

      // head is the registration transaction wich contains TOPL
      _ <- assertIO(bigBangConfig.transactions.head.outputs.size.pure[F], 1)
      // tail is the appended transactio wich contains, LVLs, Proposal, Group, Series, ...
      _ <- assertIO(bigBangConfig.transactions.tail.head.outputs.size.pure[F], 4)

      appendedOutputs = bigBangConfig.transactions.tail.head.outputs

      _ <- assertIOBoolean(
        appendedOutputs
          .map(_.value)
          .contains(
            Value.defaultInstance
              .withGroup(
                Value.Group(
                  groupId = GroupPolicyEth.computeId,
                  quantity = 1L: Int128,
                  fixedSeries = Some(SeriesPolicyEth.computeId)
                )
              )
          )
          .pure[F]
      )

      _ <- assertIOBoolean(
        appendedOutputs
          .map(_.value)
          .contains(
            Value.defaultInstance
              .withSeries(
                Value.Series(
                  seriesId = SeriesPolicyEth.computeId,
                  quantity = 1L: Int128
                )
              )
          )
          .pure[F]
      )

    } yield ()
  }

}
