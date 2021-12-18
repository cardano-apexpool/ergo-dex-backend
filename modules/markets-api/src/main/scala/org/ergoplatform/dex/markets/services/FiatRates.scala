package org.ergoplatform.dex.markets.services

import cats.{FlatMap, Functor, Monad}
import derevo.derive
import org.ergoplatform.dex.domain.{AssetClass, FiatUnits}
import org.ergoplatform.dex.markets.currencies.{UsdDecimals, UsdUnits}
import org.ergoplatform.dex.protocol.constants.ErgoAssetClass
import org.ergoplatform.ergo.models.{RegisterId, SConstant}
import org.ergoplatform.ergo.{ErgoNetwork, TokenId}
import sigmastate.SLong
import sigmastate.Values.EvaluatedValue
import sigmastate.serialization.ValueSerializer
import tofu.higherKind.Mid
import tofu.higherKind.derived.representableK
import tofu.logging.{Logging, Logs}
import tofu.syntax.foption._
import tofu.syntax.monadic._
import tofu.syntax.logging._

import scala.util.Try

@derive(representableK)
trait FiatRates[F[_]] {

  def rateOf(asset: AssetClass, units: FiatUnits): F[Option[BigDecimal]]
}

object FiatRates {

  val ErgUsdPoolNft: TokenId =
    TokenId.fromStringUnsafe("011d3364de07e5a26f0c4eef0852cddb387039a921b7154ef3cab22c6eda887f")

  def make[I[_]: Functor, F[_]: Monad](implicit
    network: ErgoNetwork[F],
    logs: Logs[I, F]
  ): I[FiatRates[F]] =
    logs
      .forService[FiatRates[F]]
      .map(implicit l => new FiatRatesTracing[F] attach new ErgoOraclesRateSource(network))

  final class ErgoOraclesRateSource[F[_]: Monad](network: ErgoNetwork[F]) extends FiatRates[F] {

    def rateOf(asset: AssetClass, units: FiatUnits): F[Option[BigDecimal]] =
      if (asset == ErgoAssetClass && units == UsdUnits) {
        network
          .getUtxoByToken(ErgUsdPoolNft, offset = 0, limit = 1)
          .map(_.headOption)
          .map {
            for {
              out    <- _
              (_, r) <- out.additionalRegisters.find { case (r, _) => r == RegisterId.R4 }
              rawValue = r match {
                           case SConstant.ByteaConstant(raw) => Try(ValueSerializer.deserialize(raw.toBytes)).toOption
                           case _                            => None
                         }
              rate <- rawValue
                        .collect { case v: EvaluatedValue[_] => v -> v.tpe }
                        .collect { case (v, SLong) => v.value.asInstanceOf[Long] }
            } yield BigDecimal(rate) / math.pow(10, UsdDecimals.toDouble)
          }
      } else noneF
  }

  final class FiatRatesTracing[F[_]: FlatMap: Logging] extends FiatRates[Mid[F, *]] {

    def rateOf(asset: AssetClass, units: FiatUnits): Mid[F, Option[BigDecimal]] =
      for {
        _ <- trace"rateOf(asset=$asset, units=$units)"
        r <- _
        _ <- trace"rateOf(asset=$asset, units=$units) -> $r"
      } yield r
  }
}
