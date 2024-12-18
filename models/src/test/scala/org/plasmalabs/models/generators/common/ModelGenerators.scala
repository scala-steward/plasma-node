package org.plasmalabs.models.generators.common

import com.google.protobuf.ByteString
import org.plasmalabs.models.utility.HasLength.instances.byteStringLength
import org.plasmalabs.models.utility.{Length, Sized}
import org.scalacheck.{Arbitrary, Gen}

trait ModelGenerators {

  implicit val arbitraryByteString: Arbitrary[ByteString] =
    Arbitrary(Arbitrary.arbByte.arbitrary.map(b => Array(b)).map(ByteString.copyFrom))

  def genSizedStrictByteString[L <: Length](
    byteGen: Gen[Byte] = Gen.choose[Byte](0, 32)
  )(implicit l: L): Gen[Sized.Strict[ByteString, L]] =
    Gen
      .containerOfN[Array, Byte](l.value, byteGen)
      .map(ByteString.copyFrom)
      .map(Sized.strict[ByteString, L](_).toOption.get)

}
object ModelGenerators extends ModelGenerators
