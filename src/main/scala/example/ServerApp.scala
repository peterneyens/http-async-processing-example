package example

import cats.~>
import cats.effect.{Clock, Concurrent, ConcurrentEffect, ContextShift, ExitCode, IO, IOApp, Sync, Timer}
import cats.syntax.flatMap._
import cats.syntax.functor._
import io.chrisdavenport.fuuid.FUUID
import io.chrisdavenport.fuuid.http4s.FUUIDVar
import io.chrisdavenport.mules.Cache
import io.circe.Json
import org.http4s.{headers, HttpRoutes, Uri}
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.middleware.Logger
import org.http4s.syntax.kleisli._
import scala.concurrent.duration.DurationInt

object ServerApp extends IOApp {

  def run(args: List[String]): IO[ExitCode] =
    CalcServer.run[IO]
}

object CalcServer {

  def run[F[_]](implicit F: ConcurrentEffect[F], cs: ContextShift[F], timer: Timer[F]): F[ExitCode] =
    for {
      cache <- Cache.createCache[F, FUUID, Int](None)
      calcWithDelay = Calc.impl[F].mapK(λ[F ~> F](timer.sleep(5.seconds) >> _))
      res <- runServer(Endpoints.http[F](calcWithDelay, cache))
    } yield res

  def runServer[F[_]](routes: HttpRoutes[F])(implicit F: ConcurrentEffect[F], cs: ContextShift[F], t: Timer[F]): F[ExitCode] =
    BlazeServerBuilder[F]
      .bindHttp(8080, "localhost")
      .withHttpApp(Logger(true, true)(routes.orNotFound))
      .serve
      .compile
      .drain
      .as(ExitCode.Success)
}

object Endpoints {
  def http[F[_]](
    calc: Calc[F],
    cache: Cache[F, FUUID, Int]
  )(implicit F: Concurrent[F], clock: Clock[F]) = {
    val dsl = new Http4sDsl[F] {}
    import dsl._

    def store(id: FUUID, result: Int): F[Unit] = cache.insert(id, result)
    def get(id: FUUID): F[Option[Int]] = cache.lookup(id)

    def resultJson(res: Int): Json = Json.obj("result" -> Json.fromInt(res))

    HttpRoutes.of[F] {
      // normal endpoint returning responses
      case GET -> Root / "sum" / IntVar(a) / IntVar(b) =>
        calc.sum(a, b).flatMap(res => Ok(resultJson(res)))

      // endpoint accepting sum request and pointing to result endpoint
      case GET -> Root / "async" / "sum" / IntVar(a) / IntVar(b) =>
        FUUID.randomFUUID[F].flatMap { correlationId =>
          F.start(calc.sum(a, b).flatMap(res => store(correlationId, res))) >>
          Accepted().map(_.putHeaders(headers.Location(Uri.uri("/async/result/sum") / correlationId.toString)))
        }

      // result endpoint that returns 
      //  `409 Conflict` while the result is not yet available
      //  `200 OK` when the result is available
      //  (no error handling at this point)
      case GET -> Root / "async" / "result" / "sum" / FUUIDVar(correlationId) =>
        get(correlationId).flatMap {
          case None => Conflict()
          case Some(result) => Ok(resultJson(result))
        }
    }
  }

}
