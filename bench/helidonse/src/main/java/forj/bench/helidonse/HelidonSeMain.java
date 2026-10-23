package forj.bench.helidonse;

import io.helidon.http.HeaderValues;
import io.helidon.http.Status;
import io.helidon.webserver.WebServer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Raw Helidon SE 4, the floor in bin/bench: Helidon's own routing and handlers, with JSON written
 * by hand, so its row is the engine with nothing on top. Same endpoints, store and port
 * ({@code FORJ_HTTP_PORT}) as forj.bench.ForjHttpMain.
 */
public final class HelidonSeMain {
    private HelidonSeMain() {
    }

    record User(long id, String name, String email) {
        byte[] json() {
            return ("{\"id\":" + id + ",\"name\":\"" + name + "\",\"email\":\"" + email + "\"}").getBytes(StandardCharsets.UTF_8);
        }
    }

    static final byte[] HELLO = "Hello, World!".getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) {
        // the names and emails are ASCII without quotes, so the hand-written JSON needs no escaping
        ConcurrentSkipListMap<Long, User> users = new ConcurrentSkipListMap<>();
        for (long i = 1; i <= 1000; i++) users.put(i, new User(i, "user" + i, "user" + i + "@example.com"));

        WebServer server = WebServer.builder()
                .port(Integer.parseInt(System.getenv().getOrDefault("FORJ_HTTP_PORT", "8080")))
                .routing(routing -> routing
                        .get("/plaintext", (req, res) -> {
                            res.header(HeaderValues.CONTENT_TYPE_TEXT_PLAIN);
                            res.send(HELLO);
                        })
                        .get("/json", (req, res) -> {
                            res.header(HeaderValues.CONTENT_TYPE_JSON);
                            // built per request, as a serializer would
                            res.send("{\"message\":\"Hello, World!\"}".getBytes(StandardCharsets.UTF_8));
                        })
                        .get("/users/{id}", (req, res) -> {
                            User user;
                            try {
                                user = users.get(Long.parseLong(req.path().pathParameters().get("id")));
                            } catch (NumberFormatException e) {
                                user = null;
                            }
                            if (user == null) {
                                res.status(Status.NOT_FOUND_404).send();
                            } else {
                                res.header(HeaderValues.CONTENT_TYPE_JSON);
                                res.send(user.json());
                            }
                        }))
                .build()
                .start();
        System.out.println("Helidon SE bench: http://localhost:" + server.port() + "/");
    }
}
