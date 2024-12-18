package org.plasmalabs.consensus.models

import org.plasmalabs.crypto.models.SignatureKesProduct
import org.plasmalabs.models as legacyModels

import legacyModels.Bytes
import legacyModels.Eta
import legacyModels.Slot
import legacyModels.Timestamp
import legacyModels.VersionId
import legacyModels.ProposalId
import legacyModels.utility.Ratio

sealed abstract class BlockHeaderValidationFailure

object BlockHeaderValidationFailures {
  case class NonForwardSlot(slot: Slot, parentSlot: Slot) extends BlockHeaderValidationFailure

  case class NonForwardTimestamp(timestamp: Timestamp, parentTimestamp: Timestamp) extends BlockHeaderValidationFailure

  case class NonForwardHeight(height: Long, parentHeight: Long) extends BlockHeaderValidationFailure

  case class TimestampSlotMismatch(blockSlot: Slot, timestamp: Timestamp) extends BlockHeaderValidationFailure

  case class SlotBeyondForwardBiasedSlotWindow(globalSlot: Slot, blockSlot: Slot) extends BlockHeaderValidationFailure

  case class InvalidVrfThreshold(threshold: Ratio) extends BlockHeaderValidationFailure

  case class IneligibleCertificate(
    threshold:              Ratio,
    eligibilityCertificate: EligibilityCertificate
  ) extends BlockHeaderValidationFailure

  case class InvalidEligibilityCertificateEta(claimedEta: Eta, actualEta: Eta) extends BlockHeaderValidationFailure

  case class InvalidEligibilityCertificateProof(proof: Bytes) extends BlockHeaderValidationFailure

  case class InvalidEligibilityCertificateNonceProof(proof: Bytes) extends BlockHeaderValidationFailure

  case class InvalidOperationalParentSignature(operationalCertificate: OperationalCertificate)
      extends BlockHeaderValidationFailure

  case class InvalidBlockProof(operationalCertificate: OperationalCertificate) extends BlockHeaderValidationFailure

  case class Unregistered(address: StakingAddress) extends BlockHeaderValidationFailure

  case class RegistrationCommitmentMismatch(
    vrfCommitment: SignatureKesProduct,
    vrfVK:         Bytes,
    poolVK:        StakingAddress
  ) extends BlockHeaderValidationFailure

  case class DuplicateEligibility(vrfVK: Bytes, slot: Slot) extends BlockHeaderValidationFailure

  case class UnsupportedVersionId(actual: VersionId, maxSupported: VersionId) extends BlockHeaderValidationFailure

  case class IncorrectVersionId(expected: VersionId, actual: VersionId) extends BlockHeaderValidationFailure

  case class IncorrectVotedVersionId(vote: VersionId) extends BlockHeaderValidationFailure

  case class IncorrectVotedProposalId(vote: ProposalId) extends BlockHeaderValidationFailure
}
