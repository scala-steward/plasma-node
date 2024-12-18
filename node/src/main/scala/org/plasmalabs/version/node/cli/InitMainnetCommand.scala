package org.plasmalabs.node.cli

import cats.effect.Async
import cats.effect.std.Console
import cats.implicits.*
import com.google.protobuf.ByteString
import fs2.io.file.{Files, Path}
import org.plasmalabs.blockchain.BigBang
import org.plasmalabs.codecs.bytes.tetra.instances.*
import org.plasmalabs.config.ApplicationConfig
import org.plasmalabs.node.ProtocolVersioner
import org.plasmalabs.node.models.FullBlock
import org.plasmalabs.quivr.models.SmallData
import org.plasmalabs.sdk.constants.NetworkConstants
import org.plasmalabs.sdk.models.box.Value
import org.plasmalabs.sdk.models.transaction.{IoTransaction, Schedule, UnspentTransactionOutput}
import org.plasmalabs.sdk.models.{Datum, Event, LockAddress, LockId}
import org.plasmalabs.sdk.syntax.*
import org.plasmalabs.typeclasses.implicits.*

object InitMainnetCommand {

  def apply[F[_]: Async: Console](appConfig: ApplicationConfig): StageResultT[F, Unit] =
    new InitMainnetCommandImpl[F](appConfig).command

  private[cli] val UnspendableLockAddress = LockAddress(
    network = NetworkConstants.MAIN_NETWORK_ID,
    ledger = NetworkConstants.MAIN_LEDGER_ID,
    id = LockId(ByteString.copyFrom(new Array[Byte](32)))
  )

}

class InitMainnetCommandImpl[F[_]: Async: Console](appConfig: ApplicationConfig) {

  import InitNetworkHelpers._

  private val intro: StageResultT[F, Unit] =
    writeMessage[F](
      "This utility initializes genesis for a public, decentralized network." +
      "  The output is several files representing the genesis block of the network."
    )

  private def validateRegistrationTransactions(
    transactions: List[IoTransaction]
  ): StageResultT[F, Option[List[IoTransaction]]] =
    if (transactions.isEmpty)
      writeMessage[F]("No transactions found").as(none[List[IoTransaction]])
    else if (transactions.count(_.outputs.exists(_.value.value.topl.exists(_.registration.nonEmpty))) == 0)
      writeMessage[F]("No registration transactions found").as(none[List[IoTransaction]])
    else
      transactions
        .traverse(tx =>
          tx.outputs.traverse(o =>
            Either.cond(
              o.address.network == NetworkConstants.MAIN_NETWORK_ID,
              (),
              show"Invalid LockAddress network for transactionId=${tx.id}"
            )
          )
        )
        .toEitherT[StageResultT[F, *]]
        .leftSemiflatTap(writeMessage[F])
        .toOption
        .as(transactions)
        .value

  private val readRegistrationTransactions: StageResultT[F, List[IoTransaction]] =
    (writeMessage[F]("Enter the directory containing the protobuf-encoded staker registration transactions.") >>
      readInput[F].flatMap {
        case "" => writeMessage[F]("Invalid directory.").as(none[List[IoTransaction]])
        case str =>
          val path = Path(str)
          StageResultT
            .liftF(Files.forAsync[F].isDirectory(path))
            .ifM(
              StageResultT
                .liftF(
                  Files
                    .forAsync[F]
                    .list(path)
                    .evalMap(Files.forAsync[F].readAll(_).compile.to(Array).map(IoTransaction.parseFrom))
                    .compile
                    .toList
                )
                .flatMap(validateRegistrationTransactions),
              writeMessage[F]("Not a directory.").as(none[List[IoTransaction]])
            )
      }).untilDefinedM
      .flatTap(_.traverse(tx => writeMessage[F](show"Loaded transactionId=${tx.id}")))

  private def readOutputDirectory(genesisBlock: FullBlock) =
    writeMessage[F]("Enter a save directory.  Leave blank to use a temporary directory.") >>
    readInput[F].semiflatMap {
      case "" =>
        Files.forAsync[F].createTempDirectory(None, s"mainnet-${genesisBlock.header.id.show}", None)
      case str =>
        Path(str).pure[F].flatTap(Files.forAsync[F].createDirectories)
    }

  private def outro(dir: Path): StageResultT[F, Unit] =
    writeMessage[F]("The mainnet genesis block has been initialized.") >>
    writeMessage[F](s"The contents of $dir should be uploaded to a public location, like GitHub.") >>
    writeMessage[F](s"The node can be launched by passing the following argument at launch: --config $dir/config.yaml")

  val command: StageResultT[F, Unit] =
    for {
      _                        <- intro
      registrationTransactions <- readRegistrationTransactions
      unstakedTopls            <- readUnstakedTopls
      lvls                     <- readLvls
      protocolSettings         <- readProtocolSettings
      timestamp                <- readTimestamp.map(_.toMillis)
      protocolUtxo = UnspentTransactionOutput(
        InitMainnetCommand.UnspendableLockAddress,
        Value.defaultInstance.withConfigProposal(protocolSettings)
      )
      tokenTransaction =
        IoTransaction(
          outputs = protocolUtxo :: unstakedTopls ++ lvls,
          datum = Datum.IoTransaction(
            Event.IoTransaction(Schedule(timestamp = timestamp), metadata = SmallData.defaultInstance)
          )
        )
      genesisConfig = BigBang.Config(
        timestamp,
        registrationTransactions :+ tokenTransaction,
        protocolVersion = ProtocolVersioner(appConfig.node.protocols).appVersion.asProtocolVersion
      )
      genesisBlock = BigBang.fromConfig(genesisConfig)
      outputDirectory <- readOutputDirectory(genesisBlock)
      _               <- saveGenesisBlock(outputDirectory)(genesisBlock)
      _               <- saveConfig(outputDirectory, genesisBlock.header.id)
      _               <- outro(outputDirectory)
    } yield ()
}
