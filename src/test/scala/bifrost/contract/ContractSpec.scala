package bifrost.contract
import bifrost.{BifrostGenerators, ValidGenerators}
import io.circe.{Json, JsonObject}
import org.scalatest.{Matchers, PropSpec}
import org.scalatest.prop.{GeneratorDrivenPropertyChecks, PropertyChecks}
import scorex.crypto.encode.Base58
import io.circe.syntax._

import scala.util.Success

class ContractSpec extends PropSpec
  with PropertyChecks
  with GeneratorDrivenPropertyChecks
  with Matchers
  with BifrostGenerators
  with ValidGenerators{

  property("Calling a method not in the contract will throw an error") {
    forAll(contractGen) {
      c: Contract => {
        forAll(stringGen.suchThat(!validContractMethods.contains(_))) {
          m: String => {
            val possibleArgs = JsonObject.empty

            val party = propositionGen.sample.get

            val result = Contract.execute(c, m)(party)(possibleArgs)
            assert(result.isFailure && result.failed.get.isInstanceOf[MatchError])
          }
        }
      }
    }
  }

  property("Calling a method with invalid party (e.g. Producer for Investor) results in Failure") {

  }

  property("Contract created from Json should have fields that match") {

  }

  property("Method executed that updates Contract should return updated Contract object") {

  }
}