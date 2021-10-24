package co.topl.crypto.mnemonic

import co.topl.crypto.mnemonic.Language.English
import co.topl.crypto.mnemonic.MnemonicSize._
import co.topl.crypto.utils.Generators.genByteArrayOfSize
import co.topl.crypto.utils.Hex
import co.topl.models.utility.Base58
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.{ScalaCheckDrivenPropertyChecks, ScalaCheckPropertyChecks}
import co.topl.models.SecretKeys
import co.topl.crypto.mnemonic.FromEntropy.Instances._

class MnemonicSpec extends AnyPropSpec with ScalaCheckPropertyChecks with ScalaCheckDrivenPropertyChecks {

  implicit val entropyAsString: FromEntropy[String] =
    (e: Entropy) => Base58.encode(e.value)

  property("12 phrase mnemonic with valid words should be valid") {
    val phrase = "cat swing flag economy stadium alone churn speed unique patch report train"
    val mnemonic = FromEntropy.derive[String](phrase, Mnemonic12, English)

    mnemonic.isRight shouldBe true
  }

  property("12 phrase mnemonic with invalid word length should be invalid") {
    val phrase = "result fresh margin life life filter vapor trim"

    val mnemonic = FromEntropy.derive[String](phrase, Mnemonic12, English)

    mnemonic.isLeft shouldBe true
  }

  property("12 phrase mnemonic with invalid words should be invalid") {
    val phrase = "amber glue hallway can truth drawer wave flex cousin grace close compose"

    val mnemonic = FromEntropy.derive[String](phrase, Mnemonic12, English)

    mnemonic.isLeft shouldBe true
  }

  property("12 phrase mnemonic with valid words and invalid checksum should be invalid") {
    val phrase = "ugly wire busy skate slice kidney razor eager bicycle struggle aerobic picnic"
    val mnemonic = FromEntropy.derive[String](phrase, Mnemonic12, English)

    mnemonic.isLeft shouldBe true
  }

  def entropyLengthTest(bytes: Int, size: MnemonicSize): Unit =
    property(s"from entropy of length $bytes should be valid") {
      forAll(genByteArrayOfSize(bytes)) { entropy: Array[Byte] =>
        if (entropy.length == bytes) {
          val entropyString = FromEntropy.derive[String](entropy, size)

          entropyString.isRight shouldBe true
        }
      }
    }

  entropyLengthTest(16, Mnemonic12)
  entropyLengthTest(20, Mnemonic15)
  entropyLengthTest(24, Mnemonic18)
  entropyLengthTest(28, Mnemonic21)
  entropyLengthTest(32, Mnemonic24)

  property("mnemonic with extra whitespace is valid") {
    val mnemonic =
      FromEntropy.derive[String](
        "vessel ladder alter error  federal sibling chat   ability sun glass valve picture",
        Mnemonic12,
        English
      )

    mnemonic.isRight shouldBe true
  }

  property("mnemonic with extra whitespace has same value as single spaced") {
    val expected =
      FromEntropy.derive[String](
        "vessel ladder alter error federal sibling chat ability sun glass valve picture",
        Mnemonic12,
        English
      ) match {
        case Left(value)  => throw new Error("failed test")
        case Right(value) => value
      }

    val result =
      FromEntropy.derive[String](
        "vessel ladder alter error  federal sibling chat   ability sun glass valve picture",
        Mnemonic12,
        English
      ) match {
        case Left(value)  => throw new Error("failed test")
        case Right(value) => value
      }

    result shouldBe expected
  }

  property("mnemonic with capital letters is valid") {
    val mnemonic = FromEntropy.derive[String](
      "Legal Winner Thank Year Wave Sausage Worth Useful Legal " +
      "Winner Thank Year Wave Sausage Worth Useful Legal Will",
      Mnemonic18,
      English
    )

    mnemonic.isRight shouldBe true
  }

  property("mnemonic with capital letters has same entropy as lowercase") {
    val expectedEntropy =
      FromEntropy.derive[String](
        "legal winner thank year wave sausage worth useful legal " +
        "winner thank year wave sausage worth useful legal will",
        Mnemonic18,
        English
      ) match {
        case Left(value)  => throw new Error("failed test")
        case Right(value) => value
      }

    val result =
      FromEntropy.derive[String](
        "Legal Winner Thank Year Wave Sausage Worth Useful Legal " +
        "Winner Thank Year Wave Sausage Worth Useful Legal Will",
        Mnemonic18,
        English
      ) match {
        case Left(value)  => throw new Error("failed test")
        case Right(value) => value
      }

    result shouldBe expectedEntropy
  }

  property("mnemonic with unusual characters is invalid") {
    val entropy =
      FromEntropy.derive[String](
        "voi\uD83D\uDD25d come effort suffer camp su\uD83D\uDD25rvey warrior heavy shoot primary" +
        " clutch c\uD83D\uDD25rush" +
        " open amazing screen " +
        "patrol group space point ten exist slush inv\uD83D\uDD25olve unfold",
        Mnemonic24,
        English
      )

    entropy.isLeft shouldBe true
  }

  case class TestVector(
    name:       String,
    phrase:     String,
    size:       MnemonicSize,
    language:   Language,
    password:   String,
    privateKey: String
  )

  // Test Vectors
  // https://topl.atlassian.net/wiki/spaces/Bifrost/pages/294813812/HD+Wallet+Protocols+and+Test+Vectors
  val testVectors = Seq(
    TestVector(
      "Test Vector #1",
      "buyer bomb chapter carbon chair grid wheel protect giraffe spike pupil model",
      Mnemonic12,
      English,
      "dinner",
      "10d6d266fc36cce2ee95197c968983b1b8a0cf26030068f0aa6d7603515d2749ff030cd54551eaeef0b2d22305d6984c1313b" +
      "855775f6bfb9fc4aff1a8aa837e43773dc6ead8b276897e03687a943ffa795f35c4dfc438f307106509ba96bf36"
    ),
    TestVector(
      "Test Vector #2",
      "vessel erase town arrow girl emotion siren better fork approve spare convince sauce amused clap",
      Mnemonic15,
      English,
      "heart",
      "105c0659a289eb1899ad891da6022155729fe7b4cd59399cf82abd5df6e7024714a4cfd8efde026641d43fb4945dbc1d83934" +
      "fbea856264f1198a4f9e34fc79dadd5982a6cb2a9ad628f4197945ab8170071af566c188c4ee35ee589a7792fed"
    ),
    TestVector(
      "Test Vector #3",
      "model abandon genius figure shiver craft surround sister permit output network swift slush lumber " +
      "dune license run sugar",
      Mnemonic18,
      English,
      "describe",
      "f8018c7179445139ab5b437e66c6109bed6879dd603a278bccec3bc6a020c25ed10a9a14b6351f0a4b68b164ada673f731738a9" +
      "62da627b399d7c5cd2fb3616a67d34d5baaf63a7c5a8159dec79de21a937a03baeb05548c91d7e2c5d648cf4e"
    ),
    TestVector(
      "Test Vector #4",
      "acquire pretty ocean screen assist purity exchange memory universe attitude sense charge fragile emerge " +
      "quick home asthma intact gloom giant gather",
      Mnemonic21,
      English,
      "manager",
      "90e7716fce3ee6b32c3a8c89d54cb01a63596c9a19c270cfcfdff29b9c585555beba8d3d4113558a3411267c454b2215fc5f1bb" +
      "429d7751f051a88942d9e3c7a8c61503b5b06f56bd3873a2fab636d87e064b3b3dca5a329646dedaab1e02c05"
    ),
    TestVector(
      "Test Vector #5",
      "nice demise viable bonus flavor genre kick nominee supreme couple tattoo shadow ethics swamp rebuild pencil " +
      "rebuild pet ignore define seek fire wrong harvest",
      Mnemonic24,
      English,
      "exact",
      "98df2e07e25a733a6d2a2636b2bd67408687fb1da399abc164ed258a98b9655f1997507da125e2d77a0c2f168a866ea8fe9a7c0e27fb" +
      "772b287a702d9742fb9fe14b0893136596df5de01ec9b6487a865bd415cb8a6ca96eb582e81777802461"
    ),
    TestVector(
      "Test Vector #6",
      "toss enrich steak utility dolphin cushion jeans work ski angle total alley trade poem february whisper toe " +
      "office half assume keep shift also fade",
      Mnemonic24,
      English,
      "",
      "686657f893d3d2c14bc3ab93d693bfbc868a621790d8aca64317834ed3aa85537d8d094a432cb852bb3b8617aeab621c0459b84a6d24" +
      "80735b4e3ff8bff11657944aaad3c8b7633eafb5871de4122241728ce5d5edd8268472c7c980252fc55b"
    )
  )

  def testVectorTest(tv: TestVector): Unit =
    property(s"Entropy Test: ${tv.name}") {
      val expectedPrivateKeyBase16 = Hex.decode(tv.privateKey)

      val pkResult =
        FromEntropy.derive[String => SecretKeys.ExtendedEd25519](tv.phrase, tv.size, tv.language) match {
          case Left(value)  => throw new Error("failed test")
          case Right(value) => value(tv.password)
        }

      val pkResultBase16 =
        Hex.encode(pkResult.leftKey.data.toArray ++ pkResult.rightKey.data.toArray ++ pkResult.chainCode.data.toArray)

      pkResultBase16 shouldBe expectedPrivateKeyBase16
    }

  testVectors.foreach(testVectorTest)
}
