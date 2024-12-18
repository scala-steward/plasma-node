package org.plasmalabs.indexer.orientDb.instances

import org.plasmalabs.indexer.orientDb.schema.OTyped.Instances.*
import org.plasmalabs.indexer.orientDb.schema.{GraphDataEncoder, OIndexable, VertexSchema}
import org.plasmalabs.sdk.codecs.AddressCodecs
import org.plasmalabs.sdk.models.{LockAddress, LockId}

object SchemaLockAddress {

  /**
   * Address model:
   * @see https://github.com/Topl/protobuf-specs/blob/main/proto/brambl/models/address.proto
   */
  object Field {
    val SchemaName = "LockAddress"
    val Network = "network"
    val Ledger = "ledger"
    // id on proto models, do not use id, Property key is reserved for all elements: id
    val AddressId = "addressId"
    val AddressEncodedId = "addressEncodedId"
    val AddressIndex = "addressIndex"
  }

  def make(): VertexSchema[LockAddress] =
    VertexSchema.create(
      Field.SchemaName,
      GraphDataEncoder[LockAddress]
        .withProperty(
          Field.Network,
          lockAddress => java.lang.Integer.valueOf(lockAddress.network),
          mandatory = true,
          readOnly = true,
          notNull = true
        )
        .withProperty(
          Field.Ledger,
          lockAddress => java.lang.Integer.valueOf(lockAddress.ledger),
          mandatory = true,
          readOnly = true,
          notNull = true
        )
        .withProperty(
          Field.AddressId,
          _.id.value.toByteArray,
          mandatory = true,
          readOnly = true,
          notNull = true
        )
        .withProperty(
          Field.AddressEncodedId,
          lockAddress => AddressCodecs.encodeAddress(lockAddress),
          mandatory = true,
          readOnly = true,
          notNull = true
        )
        .withIndex[LockAddress](Field.AddressIndex, Field.AddressId)(using OIndexable.Instances.address),
      v =>
        LockAddress(
          network = v(Field.Network),
          ledger = v(Field.Ledger),
          id = LockId.parseFrom(v(Field.AddressId))
        )
    )
}
