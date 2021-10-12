package co.topl.demo

import cats._
import cats.effect.Temporal
import cats.implicits._
import co.topl.algebras.ClockAlgebra
import co.topl.models.{Epoch, Slot, Timestamp}

import scala.concurrent.duration._

object CatsTemporalClock {

  object Eval {

    def make[F[_]: Monad: Temporal](
      _slotLength:    FiniteDuration,
      _slotsPerEpoch: Long
    ): ClockAlgebra[F] =
      new ClockAlgebra[F] {
        private val startTime = System.currentTimeMillis()

        def slotLength: F[FiniteDuration] = _slotLength.pure[F]

        def slotsPerEpoch: F[Epoch] = _slotsPerEpoch.pure[F]

        def currentEpoch(): F[Epoch] =
          (globalSlot(), slotsPerEpoch).mapN(_ / _)

        def globalSlot(): F[Slot] =
          currentTimestamp().map(currentTimestamp => (currentTimestamp - startTime) / _slotLength.toMillis)

        def currentTimestamp(): F[Timestamp] = System.currentTimeMillis().pure[F]

        def delayedUntilSlot(slot: Slot): F[Unit] =
          globalSlot()
            .map(currentSlot => (slot - currentSlot) * _slotLength)
            .flatMap(delay => if (delay.toMillis > 0) Temporal[F].sleep(delay) else Applicative[F].unit)

        def delayedUntilTimestamp(timestamp: Timestamp): F[Unit] =
          currentTimestamp()
            .map(currentTimestamp => (timestamp - currentTimestamp).millis)
            .flatMap(Temporal[F].sleep)
      }
  }
}
