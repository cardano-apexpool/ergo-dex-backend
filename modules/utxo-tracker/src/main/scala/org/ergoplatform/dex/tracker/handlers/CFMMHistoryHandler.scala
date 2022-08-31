package org.ergoplatform.dex.tracker.handlers

import cats.effect.Clock
import cats.implicits.none
import cats.instances.list._
import cats.syntax.traverse._
import cats.{Functor, FunctorFilter, Monad}
import mouse.all.anySyntaxMouse
import org.ergoplatform.ErgoAddressEncoder
import org.ergoplatform.common.streaming.{Producer, Record}
import org.ergoplatform.ergo.state.Confirmed
import org.ergoplatform.dex.domain.amm.{
  CFMMOrder,
  CFMMPool,
  CFMMVersionedOrder,
  EvaluatedCFMMOrder,
  OrderEvaluation,
  OrderId,
  PoolId
}
import org.ergoplatform.dex.protocol.amm.AMMType.{CFMMType, N2T_CFMM, T2T_CFMM}
import org.ergoplatform.dex.tracker.parsers.amm.CFMMHistoryVersionedParser._
import org.ergoplatform.dex.tracker.parsers.amm.{
  CFMMHistoryVersionedParser,
  CFMMOrdersParser,
  CFMMPoolsParser,
  VersionedCFMMParser
}
import tofu.logging.{Logging, Logs}
import tofu.streams.Evals
import tofu.syntax.foption._
import tofu.syntax.streams.all._
import tofu.syntax.monadic._
import tofu.syntax.logging._

final class CFMMHistoryHandler[
  F[_]: Monad: Evals[*[_], G]: FunctorFilter,
  G[_]: Monad: Logging
](parsers: List[CFMMHistoryVersionedParser[CFMMType, G]])(implicit
  producer: Producer[OrderId, EvaluatedCFMMOrder[CFMMVersionedOrder.Any, OrderEvaluation], F]
) {

  def handler: SettledTxHandler[F] =
    _.evalMap { tx =>
      parsers
        .traverse { parser =>
          def widen[A <: CFMMVersionedOrder.Any, E <: OrderEvaluation](eo: EvaluatedCFMMOrder[A, E]) =
            eo.copy(order = eo.order: CFMMVersionedOrder.Any, eval = eo.eval.widen[OrderEvaluation])
          for {
            deposit <- parser.deposit(tx).mapIn(widen)
            redeem  <- parser.redeem(tx).mapIn(widen)
            swap    <- parser.swap(tx).mapIn(widen)
          } yield deposit orElse redeem orElse swap
        }
        .map(_.reduce(_ orElse _))
    }.unNone
      .evalTap(op => info"Evaluated CFMM operation detected ${op.toString}")
      .map(op => Record[OrderId, EvaluatedCFMMOrder[CFMMVersionedOrder.Any, OrderEvaluation]](op.order.id, op))
      .thrush(producer.produce)
}

object CFMMHistoryHandler {

  def make[
    I[_]: Functor,
    F[_]: Monad: Evals[*[_], G]: FunctorFilter,
    G[_]: Monad: Clock
  ](implicit
    producer: Producer[OrderId, EvaluatedCFMMOrder[CFMMVersionedOrder.Any, OrderEvaluation], F],
    logs: Logs[I, G],
    e: ErgoAddressEncoder
  ): I[SettledTxHandler[F]] =
    logs.forService[CFMMHistoryHandler[F, G]].map { implicit log =>
      val parsers =
        CFMMHistoryVersionedParser[T2T_CFMM, G] ::
        CFMMHistoryVersionedParser[N2T_CFMM, G] :: Nil
      new CFMMHistoryHandler[F, G](parsers).handler
    }
}
