package forj.examples.http.users;

import static forj.http.middleware.Middlewares.*;

import forj.effect.IO;
import forj.http.HttpApp;
import forj.http.Routes;
import forj.http.helidon.HelidonBackend;
import forj.http.middleware.CorsPolicy;
import forj.http.server.Server;
import forj.http.server.ServerConfig;
import java.time.Duration;

/**
 * Serves the users API on Helidon. The port and the rest come from system properties or the
 * environment ({@code -Dforj.http.port=9000}, {@code FORJ_HTTP_PORT=9000}); 8080 by default.
 *
 * <pre>
 * ./mill examples.runMain forj.examples.http.users.Main
 * curl -XPOST localhost:8080/users -H 'Content-Type: application/json' -d '{"name":"ana","email":"ana@example.com"}'
 * curl localhost:8080/users/1
 * </pre>
 */
public final class Main {
    private Main() {
    }

    /** The whole app: the routes in IO over an in-memory repository, with the standard middleware. */
    public static HttpApp<IO> app() {
        given UserRepo<IO> repo = UserRepo.inMemory();
        Routes<IO> routes = UserRoutes.routes();
        // outside-in: the access log sees the request first and the response last
        return routes.orNotFound()
                .through(errors())
                .through(timeout(Duration.ofSeconds(10)))
                .through(cors(CorsPolicy.anyOrigin()))
                .through(requestId())
                .through(accessLog());
    }

    public static void main(String[] args) throws InterruptedException {
        try (Server server = HelidonBackend.create().start(ServerConfig.fromEnvironment(), app())) {
            System.out.println(s"forj-http users example: http://localhost:${server.port()}/");
            server.await();
        }
    }
}
