package org.plasmalabs.blockchain

import cats.implicits.*
import com.google.protobuf.ByteString
import org.plasmalabs.consensus.models.*
import org.plasmalabs.crypto.hash.Blake2b256
import org.plasmalabs.crypto.models.SecretKeyKesProduct
import org.plasmalabs.crypto.signing.{Ed25519, Ed25519VRF, KesProduct}
import org.plasmalabs.models.*
import org.plasmalabs.models.utility.*
import org.plasmalabs.quivr.models.*
import org.plasmalabs.sdk.models.*
import org.plasmalabs.sdk.models.box.*
import org.plasmalabs.sdk.models.transaction.{IoTransaction, UnspentTransactionOutput}

/**
 * Represents the data required to initialize a new staking.  This includes the necessary secret keys, plus their
 * derived verification keys and addresses.
 */
sealed abstract class StakerInitializer

object StakerInitializers {

  /**
   * An initializer for an Operator.  An Operator needs the full suite of keys in order to perform its duties.
   * @param operatorSK The operator's registration key
   * @param lockAddress The operator's lock address to associate with the registration UTxO
   * @param vrfSK The operator's VRF/eligibility/PoS key
   * @param kesSK The operator's forward-secure key
   */
  case class Operator(
    operatorSK:  ByteString,
    lockAddress: LockAddress,
    vrfSK:       ByteString,
    kesSK:       SecretKeyKesProduct
  ) extends StakerInitializer {

    val vrfVK: Bytes = ByteString.copyFrom(Ed25519VRF.precomputed().getVerificationKey(vrfSK.toByteArray))

    val operatorVK: Bytes = ByteString.copyFrom(
      new Ed25519().getVerificationKey(Ed25519.SecretKey(operatorSK.toByteArray)).bytes
    )

    val registrationSignature: SignatureKesProduct =
      new KesProduct().sign(kesSK, new Blake2b256().hash(vrfVK, operatorVK))

    val stakingAddress: StakingAddress =
      StakingAddress(
        ByteString.copyFrom(
          new Ed25519().getVerificationKey(Ed25519.SecretKey(operatorSK.toByteArray)).bytes
        )
      )

    val registration: StakingRegistration =
      StakingRegistration(stakingAddress, registrationSignature)

    /**
     * This staker's initial stake in the network
     */
    def registrationTransaction(stake: Int128): IoTransaction = {
      val toplValue =
        Value.defaultInstance.withTopl(
          Value.TOPL(stake, StakingRegistration(stakingAddress, registrationSignature).some)
        )
      val outputs = List(
        UnspentTransactionOutput(lockAddress, toplValue)
      )
      IoTransaction(inputs = Nil, outputs = outputs, datum = Datum.IoTransaction.defaultInstance)
    }
  }

  object Operator {

    /**
     * Create an Operator Staker using the given seed and KES configuration
     * @param seed 32 bytes of seed data
     * @param kesKeyHeight KES Key Height
     * @param lockAddress The LockAddress which can spend the staker's registration
     * @return an Operator Staker
     */
    def apply(
      seed:         Array[Byte],
      kesKeyHeight: (Int, Int),
      lockAddress:  LockAddress
    ): Operator = {
      // NOTE: To avoid SK collisions, each SK generated below is from
      // the hash of the given seed appended with a byte suffix
      val blake2b256 = new Blake2b256()

      val operatorSK = new Ed25519()
        .deriveKeyPairFromSeed(
          blake2b256.hash(seed :+ 1)
        )
        .signingKey
        .bytes

      val (vrfSK, _) =
        Ed25519VRF
          .precomputed()
          .deriveKeyPairFromSeed(
            blake2b256.hash(seed :+ 2)
          )
      val (kesSK, _) = new KesProduct().createKeyPair(
        seed = blake2b256.hash(seed :+ 3),
        height = kesKeyHeight,
        0
      )
      Operator(
        ByteString.copyFrom(operatorSK),
        lockAddress,
        ByteString.copyFrom(vrfSK),
        kesSK
      )
    }
  }

}
