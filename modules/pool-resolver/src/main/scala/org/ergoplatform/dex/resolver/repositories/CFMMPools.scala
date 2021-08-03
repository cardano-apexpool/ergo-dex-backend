package org.ergoplatform.dex.resolver.repositories

import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, Timer}
import cats.{FlatMap, Functor, Parallel}
import derevo.derive
import dev.profunktor.redis4cats.hlist._
import io.circe.Json
import io.circe.syntax._
import org.ergoplatform.common.cache.{Cache, MakeRedisTransaction, Redis}
import org.ergoplatform.dex.domain.amm.state.{Confirmed, Predicted}
import org.ergoplatform.dex.domain.amm.{CFMMPool, PoolId}
import org.ergoplatform.ergo.BoxId
import scodec.Codec
import scodec.codecs.{bool, utf8}
import tofu.concurrent.MakeRef
import tofu.higherKind.Mid
import tofu.higherKind.derived.representableK
import tofu.logging.{Logging, Logs}
import tofu.syntax.logging._
import tofu.syntax.monadic._

@derive(representableK)
trait CFMMPools[F[_]] {

  /** Get last predicted state of a pool with the given `id`.
    */
  def getLastPredicted(id: PoolId): F[Option[Predicted[CFMMPool]]]

  /** Get last confirmed state of a pool with the given `id`.
    */
  def getLastConfirmed(id: PoolId): F[Option[Confirmed[CFMMPool]]]

  /** Persist predicted pool.
    */
  def put(pool: Predicted[CFMMPool]): F[Unit]

  /** Persist confirmed pool state.
    */
  def put(pool: Confirmed[CFMMPool]): F[Unit]

  /** Check whether a pool state prediction with the given `id` exists.
    */
  def existsPredicted(id: BoxId): F[Boolean]
}

object CFMMPools {

  def make[I[_]: FlatMap, F[_]: Parallel: Concurrent: Timer](implicit
    redis: Redis.Plain[F],
    logs: Logs[I, F]
  ): I[CFMMPools[F]] =
    MakeRedisTransaction.make[I, F].flatMap { implicit mtx =>
      logs.forService[CFMMPools[F]] map (implicit l => new CFMMPoolsTracing[F] attach new CFMMPoolsCache[F](Cache.make))
    }

  def ephemeral[I[_]: FlatMap, F[_]: FlatMap](implicit makeRef: MakeRef[I, F], logs: Logs[I, F]): I[CFMMPools[F]] =
    for {
      store                      <- makeRef.refOf(Map.empty[String, Json])
      implicit0(log: Logging[F]) <- logs.forService[CFMMPools[F]]
    } yield new CFMMPoolsTracing[F] attach new InMemory[F](store)

  private def PredictedKey(id: BoxId)      = s"predicted:$id"
  private def LastPredictedKey(id: PoolId) = s"predicted:last:$id"
  private def LastConfirmedKey(id: PoolId) = s"confirmed:last:$id"

  final class CFMMPoolsCache[F[_]: Functor](cache: Cache[F]) extends CFMMPools[F] {

    implicit val codecString: Codec[String] = utf8
    implicit val codecBool: Codec[Boolean]  = bool

    def getLastPredicted(id: PoolId): F[Option[Predicted[CFMMPool]]] =
      cache.get[String, Predicted[CFMMPool]](LastPredictedKey(id))

    def getLastConfirmed(id: PoolId): F[Option[Confirmed[CFMMPool]]] =
      cache.get[String, Confirmed[CFMMPool]](LastConfirmedKey(id))

    def put(pool: Predicted[CFMMPool]): F[Unit] =
      cache
        .transaction(
          cache.set(LastPredictedKey(pool.predicted.poolId), pool) ::
          cache.set(PredictedKey(pool.predicted.box.boxId), true) ::
          HNil
        )
        .void

    def put(pool: Confirmed[CFMMPool]): F[Unit] =
      cache.set(LastConfirmedKey(pool.confirmed.poolId), pool)

    def existsPredicted(id: BoxId): F[Boolean] =
      cache.get[String, Boolean](PredictedKey(id)).map(_.isDefined)
  }

  final class InMemory[F[_]: Functor](store: Ref[F, Map[String, Json]]) extends CFMMPools[F] {

    def getLastPredicted(id: PoolId): F[Option[Predicted[CFMMPool]]] =
      store.get.map(_.get(LastPredictedKey(id)) >>= (_.as[Predicted[CFMMPool]].toOption))

    def getLastConfirmed(id: PoolId): F[Option[Confirmed[CFMMPool]]] =
      store.get.map(_.get(LastConfirmedKey(id)) >>= (_.as[Confirmed[CFMMPool]].toOption))

    def put(pool: Predicted[CFMMPool]): F[Unit] =
      store.update {
        _.updated(LastPredictedKey(pool.predicted.poolId), pool.asJson)
          .updated(PredictedKey(pool.predicted.box.boxId), Json.Null)
      }

    def put(pool: Confirmed[CFMMPool]): F[Unit] =
      store.update(_.updated(LastConfirmedKey(pool.confirmed.poolId), pool.asJson))

    def existsPredicted(id: BoxId): F[Boolean] =
      store.get.map(_.contains(PredictedKey(id)))
  }

  final class CFMMPoolsTracing[F[_]: FlatMap: Logging] extends CFMMPools[Mid[F, *]] {

    def getLastPredicted(id: PoolId): Mid[F, Option[Predicted[CFMMPool]]] =
      for {
        _ <- trace"getLastPredicted(id=$id)"
        r <- _
        _ <- trace"getLastPredicted(id=$id) -> $r"
      } yield r

    def getLastConfirmed(id: PoolId): Mid[F, Option[Confirmed[CFMMPool]]] =
      for {
        _ <- trace"getLastConfirmed(id=$id)"
        r <- _
        _ <- trace"getLastConfirmed(id=$id) -> $r"
      } yield r

    def put(pool: Predicted[CFMMPool]): Mid[F, Unit] =
      for {
        _ <- trace"put(pool=$pool)"
        r <- _
        _ <- trace"put(pool=$pool) -> $r"
      } yield r

    def put(pool: Confirmed[CFMMPool]): Mid[F, Unit] =
      for {
        _ <- trace"put(pool=$pool)"
        r <- _
        _ <- trace"put(pool=$pool) -> $r"
      } yield r

    def existsPredicted(id: BoxId): Mid[F, Boolean] =
      for {
        _ <- trace"existsPredicted(id=$id)"
        r <- _
        _ <- trace"existsPredicted(id=$id) -> $r"
      } yield r
  }
}