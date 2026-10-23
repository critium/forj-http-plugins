package forj.http.helidon;

import static forj.http.Dsl.*;
import static forj.http.middleware.Middlewares.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forj.effect.IO;
import forj.http.Body;
import forj.http.HttpApp;
import forj.http.Path;
import forj.http.Path1;
import forj.http.Response;
import forj.http.Routes;
import forj.http.Status;
import forj.http.server.Server;
import forj.http.server.ServerConfig;
import forj.typeclass.Sync;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** forj-http on a real Helidon server: random port, the JDK's HttpClient, shut down after. */
@SuppressWarnings("rawtypes")
class HelidonBackendTest {

    static final ServerConfig CONFIG = ServerConfig.defaults()
            .withHost("127.0.0.1")
            .withPort(0)
            .withMaxBodyBytes(1024)
            .withShutdownGrace(Duration.ofSeconds(5));

    static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    static final Path1<Long> USER = Path.of("/users").then(LongVar);

    /** Generic in F, as an app's routes are: the same ones are served in IO and in Callable. */
    static <F<_>> Routes<F> routes(AtomicInteger hits)(using Sync<F> sync) {
        return Routes.of(
                GET(USER).to((req, id) -> sync.delay(() -> {
                    hits.incrementAndGet();
                    return Ok(s"user $id, on a virtual thread: ${Thread.currentThread().isVirtual()}");
                })),
                GET("/search").respond(req -> Ok(s"q=${req.query("q").orElse("-")}")),
                POST("/echo").respond(req -> Ok(req.<String>as())),
                GET("/cookies").respond(req -> Ok("two")
                        .addHeader("Set-Cookie", "a=1")
                        .addHeader("Set-Cookie", "b=2")),
                GET("/stream").respond(req -> Ok().withBody(Body.writer(out -> {
                    for (int i = 0; i < 3; i++) out.write(s"chunk $i\n".getBytes(StandardCharsets.UTF_8));
                }, -1))),
                GET("/boom").respond(req -> { throw new IllegalStateException("secret detail"); }));
    }

    static <F<_>> HttpApp<F> app(Routes<F> routes, List<String> log)(using Sync<F> sync) {
        return routes.orNotFound().through(errors()).through(requestId()).through(accessLog(log::add));
    }

    static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return CLIENT.send(request.build(), BodyHandlers.ofString());
    }

    static HttpRequest.Builder at(Server server, String target) {
        return HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${server.port()}$target"));
    }

    @Test
    void servesAnIoApp() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        List<String> log = new CopyOnWriteArrayList<>();
        Routes<IO> routes = routes(hits);
        HttpApp<IO> app = app(routes, log);

        try (Server server = HelidonBackend.create().start(CONFIG, app)) {
            assertNotEquals(0, server.port(), "port 0 picks a free port");

            HttpResponse<String> user = send(at(server, "/users/42").header("X-Request-Id", "abc-1"));
            assertEquals(200, user.statusCode());
            assertEquals("user 42, on a virtual thread: true", user.body());
            assertEquals("text/plain; charset=utf-8", user.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("abc-1", user.headers().firstValue("X-Request-Id").orElseThrow());
            assertEquals(1, hits.get());

            assertEquals("q=a b&c", send(at(server, "/search?q=a+b%26c")).body());
            assertEquals("héllo", send(at(server, "/echo").POST(BodyPublishers.ofString("héllo"))).body());
            assertEquals(List.of("a=1", "b=2"), send(at(server, "/cookies")).headers().allValues("Set-Cookie"));
            assertEquals("chunk 0\nchunk 1\nchunk 2\n", send(at(server, "/stream")).body());

            HttpResponse<String> missing = send(at(server, "/nope"));
            assertEquals(404, missing.statusCode());
            HttpResponse<String> wrongMethod = send(at(server, "/users/1").DELETE());
            assertEquals(405, wrongMethod.statusCode());
            assertEquals("GET", wrongMethod.headers().firstValue("Allow").orElseThrow());

            HttpResponse<String> boom = send(at(server, "/boom"));
            assertEquals(500, boom.statusCode());
            assertEquals("application/problem+json", boom.headers().firstValue("Content-Type").orElseThrow());
            assertFalse(boom.body().contains("secret"), "the cause doesn't leak");

            assertTrue(log.stream().anyMatch(line -> line.startsWith("GET /users/42 200 ") && line.endsWith(" id=abc-1")), log.toString());
        }
    }

    @Test
    void servesTheSameRoutesInCallable() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        Routes<Callable> routes = routes(hits);
        HttpApp<Callable> app = app(routes, new CopyOnWriteArrayList<>());

        try (Server server = HelidonBackend.create().start(CONFIG, app)) {
            HttpResponse<String> user = send(at(server, "/users/7"));
            assertEquals(200, user.statusCode());
            assertEquals("user 7, on a virtual thread: true", user.body());
            assertEquals(1, hits.get());
        }
    }

    @Test
    void bodiesOverTheLimitAre413() throws Exception {
        Routes<IO> routes = routes(new AtomicInteger());
        HttpApp<IO> app = app(routes, new CopyOnWriteArrayList<>());
        byte[] big = new byte[2048];

        try (Server server = HelidonBackend.create().start(CONFIG, app)) {
            // declared up front: Helidon turns it away
            HttpResponse<String> declared = send(at(server, "/echo").POST(BodyPublishers.ofByteArray(big)));
            assertEquals(413, declared.statusCode());
            // chunked: found while the app reads it, answered by errors() as a problem
            HttpResponse<String> chunked = send(at(server, "/echo")
                    .POST(BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(big))));
            assertEquals(413, chunked.statusCode());
            assertEquals("application/problem+json", chunked.headers().firstValue("Content-Type").orElseThrow());
            // the server is still fine
            assertEquals(200, send(at(server, "/users/1")).statusCode());
        }
    }

    @Test
    void anAppWithoutErrorsMiddlewareStillAnswers500() throws Exception {
        Routes<IO> routes = routes(new AtomicInteger());
        HttpApp<IO> bare = routes.orNotFound();

        try (Server server = HelidonBackend.create().start(CONFIG, bare)) {
            HttpResponse<String> boom = send(at(server, "/boom"));
            assertEquals(500, boom.statusCode());
            assertEquals("", boom.body());
            assertEquals(200, send(at(server, "/users/1")).statusCode());
        }
    }

    /**
     * How streamed bodies go out. Helidon commits the response (status, headers, framing) on its
     * first flush, or on close if nothing flushed: a small body still unsent at close gets a
     * Content-Length, anything after a flush or past Helidon's buffer is chunked. So
     * {@code Body.writer} ignores the writer's flush, and only {@code Body.streaming} passes it on.
     */
    @Test
    void streamedBodies() throws Exception {
        byte[] large = new byte[64 * 1024];
        for (int i = 0; i < large.length; i++) large[i] = (byte) ('a' + i % 26);
        CountDownLatch firstEventRead = new CountDownLatch(1);
        AtomicBoolean writerWaited = new AtomicBoolean();
        Routes<IO> routes = Routes.of(
                // a writer that flushes, as forj-codec's encoder does: Body.writer swallows it
                GET("/small").respond(req -> Ok().withBody(Body.writer(out -> {
                    out.write("part1".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    out.write("part2".getBytes(StandardCharsets.UTF_8));
                }, -1))),
                GET("/large").respond(req -> Ok().withBody(Body.writer(out -> {
                    for (int i = 0; i < large.length; i += 1000) out.write(large, i, Math.min(1000, large.length - i));
                }, -1))),
                // server-sent events: the first event must reach the client while the writer still runs
                GET("/events").respond(req -> Ok().withBody(Body.streaming(out -> {
                    out.write("event 1\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    try {
                        writerWaited.set(firstEventRead.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    out.write("event 2\n".getBytes(StandardCharsets.UTF_8));
                }))));
        HttpApp<IO> app = routes.orNotFound();

        try (Server server = HelidonBackend.create().start(CONFIG, app)) {
            HttpResponse<String> small = send(at(server, "/small"));
            assertEquals("part1part2", small.body());
            assertEquals("10", small.headers().firstValue("Content-Length").orElse("none"), "the flush didn't commit it");
            assertTrue(small.headers().firstValue("Transfer-Encoding").isEmpty());

            HttpResponse<byte[]> big = CLIENT.send(at(server, "/large").build(), BodyHandlers.ofByteArray());
            assertTrue(java.util.Arrays.equals(large, big.body()), "all 64 KB arrive intact");
            assertEquals("chunked", big.headers().firstValue("Transfer-Encoding").orElse("none"));

            // the headers and the first event arrive before the writer returns, or this times out
            HttpResponse<InputStream> events = CLIENT.sendAsync(at(server, "/events").build(), BodyHandlers.ofInputStream())
                    .get(3, TimeUnit.SECONDS);
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(events.body(), StandardCharsets.UTF_8))) {
                assertEquals("event 1", lines.readLine());
                firstEventRead.countDown();
                assertEquals("event 2", lines.readLine());
            }
            assertTrue(writerWaited.get(), "the client read event 1 while the writer was still waiting");
        }
    }

    @Test
    void closeLetsRequestsInFlightFinish() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Routes<IO> routes = Routes.of(GET("/slow").to(req -> IO.delay(() -> {
            started.countDown();
            Thread.sleep(500);
            return Ok("done");
        })));
        HttpApp<IO> app = routes.orNotFound();

        Server server = HelidonBackend.create().start(CONFIG, app);
        CompletableFuture<HttpResponse<String>> slow =
                CLIENT.sendAsync(at(server, "/slow").build(), BodyHandlers.ofString());
        assertTrue(started.await(5, TimeUnit.SECONDS));

        CompletableFuture<Void> awaited = CompletableFuture.runAsync(() -> {
            try {
                server.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        server.close();
        server.close(); // a second close does nothing

        HttpResponse<String> response = slow.get(5, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertEquals("done", response.body());
        awaited.get(5, TimeUnit.SECONDS);
    }
}
