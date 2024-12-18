package org.plasmalabs.db.leveldb

import cats.Applicative
import cats.data.OptionT
import cats.effect.implicits.*
import cats.effect.{Async, Resource, Sync}
import cats.implicits.*
import com.google.protobuf.ByteString
import fs2.io.file.*
import org.iq80.leveldb.{Logger as _, *}
import org.plasmalabs.algebras.Store
import org.plasmalabs.codecs.bytes.typeclasses.Persistable
import org.plasmalabs.codecs.bytes.typeclasses.implicits.*
import org.typelevel.log4cats.Logger

import java.util.InputMismatchException
import scala.collection.mutable

/**
 * A `Store` interpreter which is backed by LevelDB.  Keys and Values must have a Persistable typeclass instance available.
 * The keys and values are encoded to and from their persistable byte representation.
 */
object LevelDbStore {

  def make[F[_]: Sync, Key: Persistable, Value: Persistable](db: DB): F[Store[F, Key, Value]] =
    Sync[F].delay {
      new Store[F, Key, Value] {

        def put(id: Key, t: Value): F[Unit] =
          (Sync[F].delay(id.persistedBytes.toByteArray), Sync[F].delay(t.persistedBytes.toByteArray)).tupled
            .flatMap { case (idB, tB) => useDb(_.put(idB, tB)) }

        def remove(id: Key): F[Unit] =
          Sync[F].delay(id.persistedBytes.toByteArray).flatMap(idB => useDb(_.delete(idB)))

        def get(id: Key): F[Option[Value]] =
          Sync[F]
            .delay(id.persistedBytes.toByteArray)
            .flatMap(idB =>
              OptionT(useDb(db => Option(db.get(idB))))
                .semiflatMap(array => getOrThrow[F, Value](array))
                .value
            )

        def contains(id: Key): F[Boolean] =
          Sync[F]
            .delay(id.persistedBytes.toByteArray)
            .flatMap(idB => useDb(_.get(idB) != null))

        def getAll(): F[Seq[(Key, Value)]] =
          useDb[Seq[(Key, Value)]] { db =>
            val ro = new ReadOptions()
            ro.snapshot(db.getSnapshot)
            val iter = db.iterator(ro)
            try {
              iter.seekToFirst()
              val bf = mutable.Buffer.empty[(Key, Value)]
              while (iter.hasNext) {
                val next = iter.next()
                val keyOpt = getAsT[Key](next.getKey).toOption
                val valueOpt = getAsT[Value](next.getValue).toOption
                keyOpt.map2(valueOpt)((key, value) => bf += (key -> value))
              }
              bf.toSeq
            } finally {
              iter.close()
              ro.snapshot().close()
            }
          }

        /**
         * Use the instance of the DB within a blocking F context
         */
        private def useDb[R](f: DB => R): F[R] =
          Sync[F].blocking(f(db))
      }
    }

  /**
   * Creates an instance of a DB from the given path
   */
  def makeDb[F[_]: Async](
    baseDirectory:   Path,
    factory:         DBFactory,
    createIfMissing: Boolean = true,
    paranoidChecks:  Option[Boolean] = None,
    blockSize:       Option[Int] = None,
    cacheSize:       Option[Long] = None,
    maxOpenFiles:    Option[Int] = None,
    compressionType: Option[CompressionType] = None
  ): Resource[F, DB] = {
    val options = new Options
    options.createIfMissing(createIfMissing)
    paranoidChecks.foreach(options.paranoidChecks)
    blockSize.foreach(options.blockSize)
    cacheSize.foreach(options.cacheSize)
    maxOpenFiles.foreach(options.maxOpenFiles)
    compressionType.foreach(options.compressionType)

    val dbF =
      Applicative[F].whenA(createIfMissing)(Files.forAsync[F].createDirectories(baseDirectory)) >>
      Sync[F].blocking {
        factory.open(
          baseDirectory.toNioPath.toFile,
          options
        )
      }

    Resource.fromAutoCloseable(dbF)
  }

  private val nativeFactory = "org.fusesource.leveldbjni.JniDBFactory"
  private val javaFactory = "org.iq80.leveldb.impl.Iq80DBFactory"

  def makeFactory[F[_]: Sync: Logger](useJni: Boolean = true): Resource[F, DBFactory] =
    Sync[F]
      .delay {
        if (useJni) {
          // As LevelDB-JNI has problems on Mac (see https://github.com/ergoplatform/ergo/issues/1067),
          // we are using only pure-Java LevelDB on Mac
          val isMac = System.getProperty("os.name").toLowerCase().indexOf("mac") >= 0
          if (isMac) List(javaFactory) else List(nativeFactory, javaFactory)
        } else
          List(javaFactory)
      }
      .flatMap(factories =>
        List(this.getClass.getClassLoader, ClassLoader.getSystemClassLoader)
          .zip(factories)
          .collectFirstSomeM { case (loader, factoryName) =>
            OptionT(
              Sync[F]
                .fromTry(
                  scala.util.Try(loader.loadClass(factoryName).getConstructor().newInstance().asInstanceOf[DBFactory])
                )
                .map(_.some)
                .recoverWith { case e =>
                  Logger[F].warn(e)(s"Failed to load database factory $factoryName").as(None)
                }
            ).map(factoryName -> _).value
          }
          .map(_.toRight(new RuntimeException(s"Could not load any of the factory classes: $factories")))
      )
      .rethrow
      .flatTap {
        case (`javaFactory`, _) =>
          Logger[F].warn(
            "Using the pure java LevelDB implementation which is experimental and slower than the native implementation."
          )
        case _ => ().pure[F]
      }
      .flatTap { case (name, factory) =>
        Logger[F].info(s"Loaded $name with $factory")
      }
      .map(_._2)
      .toResource

  def getOrThrow[F[_]: Sync, T: Persistable](data: Array[Byte]): F[T] =
    getAsT(data).toEitherT[F].rethrowT

  def getAsT[T: Persistable](data: Array[Byte]): Either[InputMismatchException, T] =
    ByteString
      .copyFrom(data)
      .decodePersisted[T]
      .leftMap(new InputMismatchException(_))

}
