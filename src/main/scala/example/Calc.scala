package example

import cats.~>
import cats.effect.Sync
import cats.syntax.functor._

trait Calc[F[_]] {
  def sum(a: Int, b: Int): F[Int]

  def mapK[G[_]](fk: F ~> G): Calc[G] =
    Calc.mapK(fk)(this)
}

object Calc {
  def impl[F[_]](implicit F: Sync[F]): Calc[F] =
    (a, b) => F.delay(println(s"sum($a, $b)")).as(a + b)

  private def mapK[F[_], G[_]](fk: F ~> G)(calc: Calc[F]): Calc[G] =
    (a, b) => fk(calc.sum(a, b))
}
