package forj.bench;

import static forj.http.Dsl.*;
import static forj.http.json.HttpJson.*;

import forj.effect.IO;
import forj.examples.http.users.NewUser;
import forj.examples.http.users.User;
import forj.examples.http.users.UserRepo;
import forj.http.Body;
import forj.http.Entity;
import forj.http.HttpApp;
import forj.http.MediaType;
import forj.http.Path;
import forj.http.Path1;
import forj.http.Routes;
import forj.http.helidon.HelidonBackend;
import forj.http.server.Server;
import forj.http.server.ServerConfig;
import forj.json.Json;
import forj.typeclass.Functor;
import java.nio.charset.StandardCharsets;

/**
 * forj-http's rows in bin/bench: the three endpoints every server in the benchmark serves, with
 * the users example's repository as the store. No middleware, since the other servers run none
 * either; the port comes from {@code FORJ_HTTP_PORT}, as for every server here.
 *
 * <ul>
 *   <li>{@code GET /plaintext}: {@code Hello, World!} as text/plain;
 *   <li>{@code GET /json}: {@code {"message":"Hello, World!"}};
 *   <li>{@code GET /users/{id}}: one of {@value #USERS} users as JSON, or 404.
 * </ul>
 *
 * <p>{@code FORJ_BENCH_JSON} picks how JSON is written, one bin/bench row each, so the rows
 * separate forj-http's own cost from the codec's:
 * <ul>
 *   <li>{@code codec} (default): {@code Ok(json(user))}, as an app writes it; forj-codec's
 *       derived encoder into a {@code byte[]};
 *   <li>{@code hand}: concatenated by hand, as the raw Helidon SE row does;
 *   <li>{@code stream}: forj-codec writing straight to the connection
 *       ({@code Json.encode(value, out)} in a {@code Body.writer}): a pooled, bounded buffer and
 *       no {@code byte[]} per response, at the price of an unknown length (chunked).
 * </ul>
 */
public final class ForjHttpMain {
    private ForjHttpMain() {
    }

    /** How many users the store starts with: ids 1 to 1000, named {@code user1}, ... */
    public static final int USERS = 1000;

    /** How JSON responses are written; see the class javadoc. */
    public enum JsonMode {
        CODEC, HAND, STREAM;

        static JsonMode fromEnvironment() {
            return valueOf(System.getenv().getOrDefault("FORJ_BENCH_JSON", "codec").toUpperCase());
        }
    }

    static final Path1<Long> USER = Path.of("/users").then(LongVar);

    static final Message HELLO = new Message("Hello, World!");

    /** Written for any {@code F}, as an app would be; bin/bench runs it in {@code IO}. */
    static <F<_>> Routes<F> routes(JsonMode mode)(using Functor<F> functor, UserRepo<F> repo) {
        return Routes.of(
                GET("/plaintext").respond(req -> Ok("Hello, World!")),
                GET("/json").respond(req -> Ok(switch (mode) {
                    case CODEC -> json(HELLO);
                    case HAND -> hand(s"{\"message\":\"${HELLO.message()}\"}");
                    case STREAM -> stream(HELLO);
                })),
                GET(USER).to((req, id) -> repo.find(id).map(found -> found.map(user -> Ok(switch (mode) {
                    case CODEC -> json(user);
                    case HAND -> hand(s"{\"id\":${user.id()},\"name\":\"${user.name()}\",\"email\":\"${user.email()}\"}");
                    case STREAM -> stream(user);
                })).orElseGet(() -> NotFound()))));
    }

    /** JSON concatenated by hand: the bench's names and emails are ASCII without quotes, so nothing needs escaping. */
    private static Entity hand(String json) {
        return new Entity(Body.of(json.getBytes(StandardCharsets.UTF_8)), MediaType.JSON);
    }

    /** forj-codec encoding into the response's output stream as Helidon sends it. */
    private static <A> Entity stream(A value)(using forj.json.Codec<A> codec, forj.json.spi.JsonBackend backend) {
        return new Entity(Body.writer(out -> Json.encode(value, out)(using codec, backend), -1), MediaType.JSON);
    }

    public static HttpApp<IO> app(JsonMode mode) throws Exception {
        given UserRepo<IO> repo = UserRepo.inMemory();
        for (int i = 1; i <= USERS; i++) IO.narrow(repo.add(new NewUser(s"user$i", s"user$i@example.com"))).unsafeRunSync();
        Routes<IO> routes = routes(mode);
        return routes.orNotFound();
    }

    public static void main(String[] args) throws Exception {
        JsonMode mode = JsonMode.fromEnvironment();
        try (Server server = HelidonBackend.create().start(ServerConfig.fromEnvironment(), app(mode))) {
            System.out.println(s"forj-http bench (JSON: ${mode.name().toLowerCase()}): http://localhost:${server.port()}/");
            server.await();
        }
    }
}
