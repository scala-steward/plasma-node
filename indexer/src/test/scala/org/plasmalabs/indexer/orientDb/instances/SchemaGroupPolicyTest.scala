package org.plasmalabs.indexer.orientDb.instances

import cats.implicits.*
import com.orientechnologies.orient.core.metadata.schema.OType
import munit.{CatsEffectFunFixtures, CatsEffectSuite, ScalaCheckEffectSuite}
import org.plasmalabs.indexer.DbFixtureUtil
import org.plasmalabs.indexer.orientDb.OrientThread
import org.plasmalabs.indexer.orientDb.instances.SchemaGroupPolicy.Field
import org.plasmalabs.indexer.orientDb.instances.VertexSchemaInstances.instances.groupPolicySchema
import org.plasmalabs.models.ModelGenerators.GenHelper
import org.plasmalabs.sdk.constants.NetworkConstants
import org.plasmalabs.sdk.generators.ModelGenerators as BramblGenerator
import org.plasmalabs.sdk.models.*
import org.plasmalabs.sdk.syntax.{groupPolicyAsGroupPolicySyntaxOps, seriesPolicyAsSeriesPolicySyntaxOps}
import org.scalamock.munit.AsyncMockFactory

import scala.jdk.CollectionConverters.*

class SchemaGroupPolicyTest
    extends CatsEffectSuite
    with ScalaCheckEffectSuite
    with AsyncMockFactory
    with CatsEffectFunFixtures
    with DbFixtureUtil {

  orientDbFixture.test("GroupPolicy Schema Metadata") { case (odbFactory, oThread: OrientThread[F]) =>
    val res = for {
      dbNoTx             <- oThread.delay(odbFactory.getNoTx).toResource
      databaseDocumentTx <- oThread.delay(dbNoTx.getRawGraph).toResource
      oClass             <- oThread.delay(databaseDocumentTx.getClass(Field.SchemaName)).toResource

      _ <- assertIO(oClass.getName.pure[F], Field.SchemaName, s"${Field.SchemaName} Class was not created").toResource

      labelProperty <- oClass.getProperty(Field.Label).pure[F].toResource
      _ <- (
        assertIO(labelProperty.getName.pure[F], Field.Label) &>
        assertIO(labelProperty.isMandatory.pure[F], true) &>
        assertIO(labelProperty.isReadonly.pure[F], true) &>
        assertIO(labelProperty.isNotNull.pure[F], true) &>
        assertIO(labelProperty.getType.pure[F], OType.STRING)
      ).toResource

      registrationUtxo <- oClass.getProperty(Field.RegistrationUtxo).pure[F].toResource
      _ <- (
        assertIO(registrationUtxo.getName.pure[F], Field.RegistrationUtxo) &>
        assertIO(registrationUtxo.isMandatory.pure[F], true) &>
        assertIO(registrationUtxo.isReadonly.pure[F], true) &>
        assertIO(registrationUtxo.isNotNull.pure[F], true) &>
        assertIO(registrationUtxo.getType.pure[F], OType.BINARY)
      ).toResource

      fixedSeriesProperty <- oClass.getProperty(Field.FixedSeries).pure[F].toResource
      _ <- (
        assertIO(fixedSeriesProperty.getName.pure[F], Field.FixedSeries) &>
        assertIO(fixedSeriesProperty.isMandatory.pure[F], false) &>
        assertIO(fixedSeriesProperty.isReadonly.pure[F], true) &>
        assertIO(fixedSeriesProperty.isNotNull.pure[F], false) &>
        assertIO(fixedSeriesProperty.getType.pure[F], OType.BINARY)
      ).toResource

    } yield ()

    res.use_

  }

  orientDbFixture.test("GroupPolicy Schema Add vertex") { case (odbFactory, oThread: OrientThread[F]) =>
    val res = for {

      dbTx          <- oThread.delay(odbFactory.getTx).toResource
      transactionId <- BramblGenerator.arbitraryTransactionId.arbitrary.first.pure[F].toResource

      registrationUtxo = TransactionOutputAddress(
        NetworkConstants.PRIVATE_NETWORK_ID,
        NetworkConstants.MAIN_LEDGER_ID,
        0,
        transactionId
      )
      seriesPolicy = SeriesPolicy(label = "Crypto Frogs series", tokenSupply = None, registrationUtxo)
      groupPolicy = GroupPolicy(label = "Crypto Frogs", registrationUtxo, Some(seriesPolicy.computeId))

      vertex_test_1 <- oThread.delay {
        val v = dbTx.addVertex(s"class:${groupPolicySchema.name}", groupPolicySchema.encode(groupPolicy).asJava)
        v.save()
        dbTx.commit()
        v
      }.toResource

      _ = assert(
        vertex_test_1
          .getProperty[Array[Byte]](groupPolicySchema.properties.filter(_.name == Field.GroupPolicyId).head.name)
          .toSeq
          == groupPolicy.computeId.value.toByteArray.toSeq
      )

      _ = assert(
        vertex_test_1
          .getProperty[String](groupPolicySchema.properties.filter(_.name == Field.Label).head.name)
          == groupPolicy.label
      )

      _ = assert(
        vertex_test_1
          .getProperty[Array[Byte]](groupPolicySchema.properties.filter(_.name == Field.RegistrationUtxo).head.name)
          .toSeq
          ==
            groupPolicy.registrationUtxo.toByteArray.toSeq
      )

      _ = assert(
        vertex_test_1
          .getProperty[Array[Byte]](groupPolicySchema.properties.filter(_.name == Field.FixedSeries).head.name)
          .toSeq
          ==
            groupPolicy.fixedSeries.get.value.toByteArray.toSeq
      )

      groupPolicyDecoded = groupPolicySchema.decode(vertex_test_1)
      _ = assert(groupPolicyDecoded == groupPolicy)

      registrationUtxo = TransactionOutputAddress(1, 1, 1, transactionId)
      groupPolicyWithNoneSeries = GroupPolicy(label = "Crypto Frogs with None fixed series", registrationUtxo, None)

      vertex_test_2 <- oThread
        .delay(
          dbTx.addVertex(s"class:${groupPolicySchema.name}", groupPolicySchema.encode(groupPolicyWithNoneSeries).asJava)
        )
        .toResource

      _ = assert(
        vertex_test_2
          .getProperty[Array[Byte]](groupPolicySchema.properties.filter(_.name == Field.GroupPolicyId).head.name)
          .toSeq
          == groupPolicyWithNoneSeries.computeId.value.toByteArray.toSeq
      )

      _ = assert(
        vertex_test_2
          .getProperty[String](groupPolicySchema.properties.filter(_.name == Field.Label).head.name)
          == groupPolicyWithNoneSeries.label
      )

      _ = assert(
        vertex_test_2
          .getProperty[Array[Byte]](groupPolicySchema.properties.filter(_.name == Field.RegistrationUtxo).head.name)
          .toSeq ==
          groupPolicyWithNoneSeries.registrationUtxo.toByteArray.toSeq
      )

      _ = assert(
        vertex_test_2
          .getProperty[Array[Byte]](groupPolicySchema.properties.filter(_.name == Field.FixedSeries).head.name)
          .toSeq ==
          Array.empty[Byte].toSeq
      )

      groupPolicyDecoded = groupPolicySchema.decode(vertex_test_2)
      _ = assert(groupPolicyDecoded == groupPolicyWithNoneSeries)

    } yield ()
    res.use_

  }
}
