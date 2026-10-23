package forj.http.helidon;

import forj.http.server.Server;
import io.helidon.webserver.WebServer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A running Helidon server. Closing it (or the JVM exiting) stops Helidon, which stops
 * accepting connections and lets requests in flight finish for up to the configured grace.
 */
final class HelidonServer implements Server {
    private final WebServer server;
    private final int port;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final Thread hook;

    HelidonServer(WebServer server) {
        this.server = server;
        this.port = server.port();
        this.hook = Thread.ofPlatform().name("forj-http-shutdown").unstarted(this::stop);
        Runtime.getRuntime().addShutdownHook(hook);
    }

    @Override
    public int port() {
        return port;
    }

    @Override
    public void await() throws InterruptedException {
        stopped.await();
    }

    @Override
    public void close() {
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException alreadyShuttingDown) {
            // the hook runs (or ran) stop() itself
        }
        stop();
    }

    private void stop() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            server.stop();
        } finally {
            stopped.countDown();
        }
    }

    @Override
    public String toString() {
        return s"HelidonServer(port $port)";
    }
}
