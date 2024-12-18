package org.plasmalabs.catsutils

import cats.Monad
import cats.effect.implicits.*
import cats.effect.{Async, Clock}
import org.typelevel.log4cats.Logger

import scala.concurrent.duration.*
import scala.language.implicitConversions

trait FOps {

  implicit def faAsFAClockOps[F[_], A](fa: F[A]): FAClockOps[F, A] =
    new FAClockOps(fa)
}

class FAClockOps[F[_], A](val fa: F[A]) extends AnyVal {
  import cats.implicits._

  /**
   * Wraps the `fa` with a timer that measures the execution length of `fa`.  The resulting duration is logged as
   * a `trace`.
   * @param operationName The name of the operation to include in the log message
   * @return `fa` that is wrapped with a timer+log
   */
  def logDuration(operationName: String)(implicit fMonad: Monad[F], fClock: Clock[F], fLogger: Logger[F]): F[A] =
    Clock[F]
      .timed(fa)
      .flatMap { case (duration, result) =>
        Logger[F]
          .info(show"$operationName duration=${duration.toMillis}ms")
          .as(result)
      }

  /**
   * Wraps the `fa` with a timer that measures the execution length of `fa`.  The resulting duration is logged as
   * a `trace`.
   * @param operationNameF A function which returns an operation name when given the result value
   * @return `fa` that is wrapped with a timer+log
   */
  def logDurationRes(
    operationNameF: A => String
  )(implicit fMonad: Monad[F], fClock: Clock[F], fLogger: Logger[F]): F[A] =
    Clock[F]
      .timed(fa)
      .flatMap { case (duration, result) =>
        Logger[F]
          .info(show"${operationNameF(result)} duration=${duration.toMillis}ms")
          .as(result)
      }

  /**
   * Evaluates `fa`.  In the background, also evaluates a sleep operation followed by a stream of log messages.  If
   * `fa` completes before the sleep completes, no logs are printed.
   * @param operationName The name of the operation to include in the log message
   * @param threshold The initial sleep delay (the threshold at which the operation is considered "slow")
   * @param logTick If the threshold elapses, log messages will be printed at this interval
   * @return `fa`
   */
  def warnIfSlow(operationName: String, threshold: FiniteDuration = 500.milli, logTick: FiniteDuration = 1.seconds)(
    implicit
    fAsync:  Async[F],
    fLogger: Logger[F]
  ): F[A] =
    fAsync.realTime
      .flatTap(_ => fAsync.delayBy(().pure[F], threshold))
      .flatMap(start =>
        fAsync.realTime
          .flatMap(now => fLogger.warn(s"$operationName is slow.  Elapsed duration=${(now - start).toMillis}ms"))
          .andWait(logTick)
          .void
          .foreverM[Unit]
      )
      .background
      .surround(fa)
}
