package co.topl.node

import cats.Show
import mainargs._

@main
case class Args(startup: Args.Startup, runtime: Args.Runtime)

object Args {

  @main
  case class Startup(
    @arg(
      doc = "Zero or more config files (.conf, .json, .yaml) to apply to the node." +
        "  Config files stack such that the last config file takes precedence."
    )
    config: Seq[String] = Nil,
    @arg(
      doc = "An optional path to a logback.xml file to override the logging configuration of the node."
    )
    logbackFile: Option[String] = None,
    @arg(
      doc = "An optional flag to enable debug mode on this node."
    )
    debug: Flag
  )

  @main
  case class Runtime(
    @arg(
      doc = "The directory to use when saving/reading blockchain data"
    )
    dataDir: Option[String],
    @arg(
      doc = "The directory of the block producer's staking keys"
    )
    stakingDir: Option[String],
    @arg(
      doc = "The hostname to bind to for the RPC layer (i.e. localhost or 0.0.0.0)"
    )
    rpcBindHost: Option[String] = None,
    @arg(
      doc = "The port to bind to for the RPC layer (i.e. 9085)"
    )
    rpcBindPort: Option[Int] = None,
    @arg(
      doc = "The hostname to bind to for the P2P layer (i.e. localhost or 0.0.0.0)"
    )
    p2pBindHost: Option[String] = None,
    @arg(
      doc = "The port to bind to for the P2P layer (i.e. 9084)"
    )
    p2pBindPort: Option[Int] = None,
    @arg(
      doc = "A comma-delimited list of host:port values to connect to at launch (i.e. 1.2.3.4:9084,5.6.7.8:9084)"
    )
    knownPeers: Option[String] = None
  )

  implicit val showArgs: Show[Args] =
    Show.fromToString

  implicit val parserStartup: ParserForClass[Startup] =
    ParserForClass[Startup]

  implicit val parserRuntime: ParserForClass[Runtime] =
    ParserForClass[Runtime]

  implicit val parserArgs: ParserForClass[Args] =
    ParserForClass[Args]
}
