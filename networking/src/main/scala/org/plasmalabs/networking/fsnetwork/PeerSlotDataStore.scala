package org.plasmalabs.networking.fsnetwork

import cats.effect.Sync
import cats.implicits.*
import com.github.benmanes.caffeine.cache.Caffeine
import org.plasmalabs.algebras.{Store, StoreReader}
import org.plasmalabs.consensus.models.{BlockId, SlotData}
import scalacache.Entry
import scalacache.caffeine.CaffeineCache

case class PeerSlotDataStoreConfig(cacheSize: Long)

object PeerSlotDataStore {

  def make[F[_]: Sync](
    slotStoreReader: StoreReader[F, BlockId, SlotData],
    config:          PeerSlotDataStoreConfig
  ): Store[F, BlockId, SlotData] = {
    val cache = CaffeineCache(
      Caffeine.newBuilder
        .maximumSize(config.cacheSize)
        .build[BlockId, Entry[SlotData]]()
    )

    new Store[F, BlockId, SlotData] {
      override def put(id: BlockId, t: SlotData): F[Unit] = cache.put(id)(t)

      override def remove(id: BlockId): F[Unit] = cache.remove(id)

      override def get(id: BlockId): F[Option[SlotData]] =
        // underlying storage could be ContainsCacheStore thus use contains function first
        Sync[F].ifM(slotStoreReader.contains(id))(
          ifTrue = slotStoreReader.get(id),
          ifFalse = cache.get(id)
        )

      override def contains(id: BlockId): F[Boolean] =
        Sync[F].ifM(slotStoreReader.contains(id))(
          ifTrue = true.pure[F],
          ifFalse = cache.get(id).map(_.isDefined)
        )

      override def getAll(): F[Seq[(BlockId, SlotData)]] = slotStoreReader.getAll()
    }
  }
}
