package forj.http.helidon;

import forj.http.HttpApp;
import forj.http.server.Backend;
import forj.http.server.Server;
import forj.http.server.ServerConfig;
import forj.typeclass.Effect;
import io.helidon.webserver.WebServer;

/**
 * forj-http on Helidon WebServer 4. Helidon already runs each request on its own virtual
 * thread, so the app runs right there, blocking: Helidon's handler builds the
 * {@link forj.http.Request}, runs {@code app.run(request)} with {@code effect.unsafeRunSync},
 * and writes the {@link forj.http.Response}. There's no hand-off to another pool.
 *
 * <pre>{@code
 * try (Server server = HelidonBackend.create().start(ServerConfig.fromEnvironment(), app)) {
 *     server.await();
 * }
 * }</pre>
 *
 * <p>Helidon serves only this app: there's no Helidon routing, media support or error page
 * in the way. Its own features stay as they are (HTTP/1.1, keep-alive, pipelining).
 */
public final class HelidonBackend implements Backend {
    private static final HelidonBackend INSTANCE = new HelidonBackend();

    private HelidonBackend() {
    }

    public static HelidonBackend create() {
        return INSTANCE;
    }

    @Override
    public <F<_>> Server start(ServerConfig config, HttpApp<F> app)(using Effect<F> effect) {
        WebServer server = WebServer.builder()
                .host(config.host())
                .port(config.port())
                // no limit in Helidon: AppHandler applies maxBodyBytes, so an oversized body is a
                // 413 problem response whether it's declared up front or found while reading
                .maxPayloadSize(-1)
                .idleConnectionTimeout(config.idleTimeout())
                .shutdownGracePeriod(config.shutdownGrace())
                // HelidonServer adds its own hook, so close() and a JVM exit drain the same way
                .shutdownHook(false)
                .routing(routing -> routing.any(new AppHandler<F>(app, effect, config.maxBodyBytes())))
                .build()
                .start();
        return new HelidonServer(server);
    }
}
