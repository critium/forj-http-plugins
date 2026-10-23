package forj.bench.http4s

import cats.effect.{IO, IOApp}
import com.comcast.ip4s.{Port, ipv4}
import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, writeToArray}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import java.util.concurrent.ConcurrentSkipListMap
import org.http4s.{HttpRoutes, MediaType, Response}
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.`Content-Type`

final case class Message(message: String)
final case class User(id: Long, name: String, email: String)

/**
 * http4s on Ember in bin/bench, with jsoniter-scala codecs (compile-time derived, the fastest on
 * the JVM) so the JSON rows compare frameworks rather than codecs. Same endpoints, store and
 * port (FORJ_HTTP_PORT) as forj.bench.ForjHttpMain; the store is read in IO, as forj-http's is.
 */
object Http4sMain extends IOApp.Simple:
  given JsonValueCodec[Message] = JsonCodecMaker.make
  given JsonValueCodec[User] = JsonCodecMaker.make

  private val users = ConcurrentSkipListMap[Long, User]()
  (1L to 1000L).foreach(i => users.put(i, User(i, s"user$i", s"user$i@example.com")))

  private def json[A: JsonValueCodec](a: A): IO[Response[IO]] =
    Ok(writeToArray(a)).map(_.withContentType(`Content-Type`(MediaType.application.json)))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "plaintext" => Ok("Hello, World!")
    case GET -> Root / "json" => json(Message("Hello, World!"))
    case GET -> Root / "users" / LongVar(id) =>
      IO(Option(users.get(id))).flatMap(_.fold(NotFound())(json))
  }

  def run: IO[Unit] =
    val port = Port.fromString(sys.env.getOrElse("FORJ_HTTP_PORT", "8080")).get
    EmberServerBuilder
      .default[IO]
      .withHost(ipv4"0.0.0.0")
      .withPort(port)
      .withHttpApp(routes.orNotFound)
      .build
      .evalTap(server => IO.println(s"http4s Ember bench: http://localhost:${server.address.getPort}/"))
      .useForever
