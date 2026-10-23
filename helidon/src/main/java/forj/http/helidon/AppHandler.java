package forj.http.helidon;

import forj.http.Attributes;
import forj.http.Body;
import forj.http.Headers;
import forj.http.HttpApp;
import forj.http.HttpFailure;
import forj.http.Method;
import forj.http.Request;
import forj.http.Response;
import forj.http.Uri;
import forj.typeclass.Effect;
import io.helidon.http.Header;
import io.helidon.http.HeaderNames;
import io.helidon.http.HttpPrologue;
import io.helidon.http.ServerResponseHeaders;
import io.helidon.http.Status;
import io.helidon.webserver.http.Handler;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.OptionalLong;

/** The one Helidon handler: Helidon's request in, the app run on this virtual thread, its response out. */
final class AppHandler<F<_>> implements Handler {
    private static final System.Logger LOG = System.getLogger("forj.http.helidon");

    private final HttpApp<F> app;
    private final Effect<F> effect;
    private final long maxBodyBytes;

    AppHandler(HttpApp<F> app, Effect<F> effect, long maxBodyBytes) {
        this.app = app;
        this.effect = effect;
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    public void handle(ServerRequest req, ServerResponse res) {
        Response response;
        try {
            response = tooLarge(req)
                    ? new HttpFailure(forj.http.Status.CONTENT_TOO_LARGE, s"the body is larger than $maxBodyBytes bytes").toResponse()
                    : effect.unsafeRunSync(app.run(request(req)));
        } catch (Throwable e) {
            // no errors() middleware, or a failure inside it: a bare 500, never a dropped connection
            LOG.log(System.Logger.Level.ERROR, s"${req.prologue().method().text()} ${req.prologue().uriPath().rawPath()} failed", e);
            if (!res.isSent()) res.status(Status.INTERNAL_SERVER_ERROR_500).send();
            return;
        }
        try {
            write(response, res);
        } catch (Exception e) {
            // the status line may be gone already: all that's left is to log it, and Helidon closes the connection
            LOG.log(System.Logger.Level.WARNING, s"writing the response to ${req.prologue().uriPath().rawPath()} failed", e);
        }
    }

    /** A declared body over the limit is turned away before the app runs, so it's never read. */
    private boolean tooLarge(ServerRequest req) {
        OptionalLong length = req.headers().contentLength();
        return length.isPresent() && length.getAsLong() > maxBodyBytes;
    }

    private Request request(ServerRequest req) {
        HttpPrologue prologue = req.prologue();
        String query = prologue.hasQuery() ? prologue.query().rawValue() : null;
        return new Request(
                Method.of(prologue.method().text()),
                Uri.of(prologue.uriPath().rawPath(), query),
                headers(req),
                body(req),
                Attributes.EMPTY);
    }

    private static Headers headers(ServerRequest req) {
        io.helidon.http.Headers headers = req.headers();
        String[] pairs = new String[headers.size() * 2];
        int i = 0;
        for (Header header : headers) {
            for (String value : header.allValues()) {
                if (i == pairs.length) pairs = Arrays.copyOf(pairs, pairs.length * 2);
                pairs[i++] = header.name();
                pairs[i++] = value;
            }
        }
        return i == 0 ? Headers.EMPTY : Headers.of(i == pairs.length ? pairs : Arrays.copyOf(pairs, i));
    }

    private Body body(ServerRequest req) {
        if (!req.content().hasEntity()) return Body.EMPTY;
        OptionalLong length = req.headers().contentLength();
        // a declared length was checked already (tooLarge); a chunked body is counted as it's read
        return Body.of(new LimitedInputStream(req.content().inputStream(), maxBodyBytes), length.orElse(-1));
    }

    private static void write(Response response, ServerResponse res) throws Exception {
        forj.http.Status status = response.status();
        res.status(Status.create(status.code(), status.reason()));
        ServerResponseHeaders headers = res.headers();
        response.headers().forEach((name, value) -> headers.add(HeaderNames.create(name), value));
        switch (response.body()) {
            case Body.Bytes bytes -> res.send(bytes.bytes());
            case Body body -> {
                if (body.length() >= 0) res.contentLength(body.length());
                try (OutputStream out = res.outputStream()) {
                    body.writeTo(out);
                }
            }
        }
    }
}
