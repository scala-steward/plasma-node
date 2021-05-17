package co.topl.rpc.handlers

import cats.data.EitherT
import cats.implicits._
import co.topl.akkahttprpc.{InvalidParametersError, RpcError, ThrowableData}
import co.topl.attestation.AddressCodec.implicits.StringOps
import co.topl.crypto.hash.Blake2b256
import co.topl.crypto.hash.digest.implicits._
import co.topl.modifier.box.AssetCode
import co.topl.rpc.{ToplRpc, ToplRpcErrors}
import co.topl.utils.AsBytes.implicits._
import co.topl.utils.NetworkType
import co.topl.utils.NetworkType.NetworkPrefix
import co.topl.utils.codecs.CryptoCodec.implicits._
import co.topl.utils.encode.Base58
import io.circe.Encoder

import java.security.SecureRandom
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class UtilsRpcHandlerImpls(implicit
  throwableEncoder: Encoder[ThrowableData],
  ec:               ExecutionContext,
  networkPrefix:    NetworkPrefix
) extends ToplRpcHandlers.Utils {

  import UtilsRpcHandlerImpls._

  val defaultSeedSize = 32 // todo: JAA - read this from a more appropriate place. Bip39 spec or something?

  override val seed: ToplRpc.Util.Seed.rpc.ServerHandler =
    _ => EitherT.pure[Future, RpcError](ToplRpc.Util.Seed.Response(generateSeed(defaultSeedSize)))

  override val seedOfLength: ToplRpc.Util.SeedOfLength.rpc.ServerHandler =
    params => EitherT.pure[Future, RpcError](ToplRpc.Util.SeedOfLength.Response(generateSeed(params.length)))

  override val hashBlake2b256: ToplRpc.Util.HashBlake2b256.rpc.ServerHandler =
    params =>
      Blake2b256
        .hash(params.message.getBytes)
        .map(hash => ToplRpc.Util.HashBlake2b256.Response(params.message, Base58.encode(hash)))
        .leftMap(err => InvalidParametersError.adhoc(err.toString, "message"): RpcError)
        .toEitherT[Future]

  override val generateAssetCode: ToplRpc.Util.GenerateAssetCode.rpc.ServerHandler =
    params =>
      Try(AssetCode(params.version, params.issuer, params.shortName)).toEither
        .leftMap(ToplRpcErrors.FailedToGenerateAssetCode(_): RpcError)
        .map(ToplRpc.Util.GenerateAssetCode.Response)
        .toEitherT[Future]

  override val checkValidAddress: ToplRpc.Util.CheckValidAddress.rpc.ServerHandler =
    params =>
      params.network
        .fold(NetworkType.pickNetworkType(networkPrefix))(NetworkType.pickNetworkType)
        .toRight(ToplRpcErrors.InvalidNetworkSpecified)
        .flatMap(nt =>
          params.address
            .decodeAddress(nt.netPrefix)
            .toEither
            .leftMap(e => InvalidParametersError.adhoc(e.head.toString, "address"): RpcError)
            .map(address => ToplRpc.Util.CheckValidAddress.Response(address, nt.verboseName))
        )
        .toEitherT[Future]
}

object UtilsRpcHandlerImpls {

  private def generateSeed(length: Int): String = {
    val seed = new Array[Byte](length)
    new SecureRandom().nextBytes(seed) //seed mutated here!
    Base58.encode(seed)
  }
}
