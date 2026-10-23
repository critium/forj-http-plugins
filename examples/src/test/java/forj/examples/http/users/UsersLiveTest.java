package forj.examples.http.users;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import forj.http.helidon.HelidonBackend;
import forj.http.server.Server;
import forj.http.server.ServerConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import org.junit.jupiter.api.Test;

/** Main's app on a real Helidon server: a random port, the JDK's HttpClient, shut down after. */
class UsersLiveTest {

    static final HttpClient CLIENT = HttpClient.newHttpClient();

    @Test
    void servesTheUsersApi() throws Exception {
        try (Server server = HelidonBackend.create().start(ServerConfig.defaults().withHost("127.0.0.1").withPort(0), Main.app())) {
            String base = s"http://127.0.0.1:${server.port()}";

            HttpResponse<String> created = CLIENT.send(HttpRequest.newBuilder(URI.create(s"$base/users"))
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString("{\"name\":\"ana\",\"email\":\"ana@example.com\"}"))
                    .build(), BodyHandlers.ofString());
            assertEquals(201, created.statusCode(), created.body());
            assertEquals("/users/1", created.headers().firstValue("Location").orElseThrow());
            assertTrue(created.headers().firstValue("X-Request-Id").isPresent(), "requestId() is in the stack");

            HttpResponse<String> found = CLIENT.send(HttpRequest.newBuilder(URI.create(s"$base/users/1"))
                    .header("Origin", "https://example.com")
                    .build(), BodyHandlers.ofString());
            assertEquals(200, found.statusCode());
            assertEquals("{\"id\":1,\"name\":\"ana\",\"email\":\"ana@example.com\"}", found.body());
            assertEquals("*", found.headers().firstValue("Access-Control-Allow-Origin").orElseThrow(), "cors() is in the stack");

            HttpResponse<String> bad = CLIENT.send(HttpRequest.newBuilder(URI.create(s"$base/users"))
                    .header("Content-Type", "application/json")
                    .POST(BodyPublishers.ofString("{nope"))
                    .build(), BodyHandlers.ofString());
            assertEquals(400, bad.statusCode());
            assertEquals("application/problem+json", bad.headers().firstValue("Content-Type").orElseThrow());
        }
    }
}
