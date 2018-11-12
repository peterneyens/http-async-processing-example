package example

import cats.~>
import cats.effect.{Clock, Concurrent, ConcurrentEffect, ContextShift, Effect, ExitCode, IO, IOApp, Sync, Timer}
import cats.instances.int._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.eq._
import cats.syntax.option._
import io.circe.Decoder
import io.chrisdavenport.log4cats.Logger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import fs2.Stream
import org.http4s.{EntityDecoder, Method, Request, Response, Status}
import org.http4s.{headers, Uri}
import org.http4s.circe._
import org.http4s.client.{Client, UnexpectedStatus}
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.client.middleware.{Retry, RetryPolicy, Logger => LoggerMiddleware}
import scala.concurrent.duration.DurationInt

object ClientApp extends IOApp {

  def run(args: List[String]): IO[ExitCode] =
    CalcClient.run[IO]
}

object CalcClient {
  
  def run[F[_]](implicit F: ConcurrentEffect[F], timer: Timer[F]): F[ExitCode] =
    BlazeClientBuilder[F](scala.concurrent.ExecutionContext.Implicits.global)
      // changing the number of connections influences `calc` much more than `calcAsync`.
      //   calc duration ~= (10 / nbConnections) * 5 seconds
      //   calc async duration ~= 5.xxx seconds
      .withMaxTotalConnections(3)
      // these timeouts need to be longer than usual, because with the normal endpoint
      // request can timeout if the max number of connections is small
      .withResponseHeaderTimeout(30.seconds)
      .withRequestTimeout(30.seconds)
      .withIdleTimeout(30.seconds)
      .resource
      .use { c =>
        val client = LoggerMiddleware(true, true)(c)
        for {
          logger  <- Slf4jLogger.create[F]
          _       <- logger.info("calc")
          (_, d1) <- duration(runSums(calc[F](client), logger))
          _       <- logger.info("------------------------")
          _       <- timer.sleep(1.second)
          _       <- logger.info("calc async")
          (_, d2) <- duration(runSums(calcAsync[F](client), logger))
          _       <- logger.info("------------------------")
          _       <- logger.info(s"Duration 1: $d1")
          _       <- logger.info(s"Duration 2: $d2")
          _       <- logger.info(s"Relative: ${((BigDecimal(d2) / BigDecimal(d1)) * 100).setScale(5, BigDecimal.RoundingMode.HALF_UP)} %")
        } yield ExitCode.Success
      }

  // send 10 concurrent sum requests (1+1, 2+2, ...)
  private def runSums[F[_]](calc: Calc[F], logger: Logger[F])(implicit F: Concurrent[F]): F[Unit] =
    Stream
      .emits(1 to 10)
      .covary[F]
      .map(i => Stream.eval(calc.sum(i, i).flatMap(res => logger.info(s"Client: $i + $i = $res"))))
      .parJoinUnbounded
      .compile
      .drain

  private def duration[F[_], A](fa: F[A])(implicit F: Sync[F], clock: Clock[F]): F[(A, Long)] =
    for {
      t1 <- clock.monotonic(scala.concurrent.duration.NANOSECONDS)
      res <- fa
      t2 <- clock.monotonic(scala.concurrent.duration.NANOSECONDS)
    } yield (res, t2 - t1)


  private val baseUri = Uri.uri("http://localhost:8080/") 

  // Calc implementation calling the normal endpoint of the server
  private def calc[F[_]](client: Client[F])(implicit F: Sync[F]): Calc[F] = {
    implicit val entityDecoder: EntityDecoder[F, Result] = jsonOf

    (a, b) => client.expect[Result](baseUri / "sum" / a.toString / b.toString).map(_.result)
  }

  // Calc implementation calling the two "async" endpoint of the server (the accept and result endpoints)
  private def calcAsync[F[_]](client: Client[F])(implicit F: Sync[F], timer: Timer[F]): Calc[F] = {
    implicit val entityDecoder: EntityDecoder[F, Result] = jsonOf

    val initialWait = 2.seconds
    val maxWait = 12.seconds
    val wait = 500.millis
    val nbAttempts = ((maxWait - initialWait)/ wait).toInt

    (a, b) =>
      // initial request to the async sum endpoint
      client.fetch[Uri](Request[F](Method.GET, baseUri / "async" / "sum" / a.toString / b.toString)) {
        case Status.Accepted(resp) =>
          headers.Location.from(resp.headers)
            .map(_.uri).liftTo[F][Throwable](new RuntimeException("Accepted without result location"))
        case resp => F.raiseError(UnexpectedStatus(resp.status))
      }.flatMap { resultUri =>
        // wait `initialWait` and then retry every `wait` to get the `Result`
        // using the uri received in the `Accepted` response
        // as long as the response status is `Conflig` and up to `nbAttempts` times
        Stream.retry(
          client.expect[Result](baseUri.resolve(resultUri)).map(_.result),
          initialWait,
          _ => wait,
          nbAttempts,
          {
            case UnexpectedStatus(Status.Conflict) => true
            case _ => false
          }
        ).compile.lastOrError
      }   
  }

  private final case class Result(result: Int)
  private object Result {
    implicit val resultDecoder: Decoder[Result] =
      _.get[Int]("result").map(Result.apply)
  }
}
