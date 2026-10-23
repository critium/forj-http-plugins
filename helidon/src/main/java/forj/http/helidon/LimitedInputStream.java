package forj.http.helidon;

import forj.http.HttpFailure;
import forj.http.Status;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A request body that fails with a 413 {@link HttpFailure} past {@code limit} bytes, so the
 * {@link forj.http.middleware.Middlewares#errors errors} middleware answers it like any other
 * client error. Helidon checks a declared {@code Content-Length} up front; this catches a chunked
 * body as it's read.
 */
final class LimitedInputStream extends FilterInputStream {
    private final long limit;
    private long read;

    LimitedInputStream(InputStream in, long limit) {
        super(in);
        this.limit = limit;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) count(1);
        return b;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        int n = super.read(buf, off, len);
        if (n > 0) count(n);
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = super.skip(n);
        count(skipped);
        return skipped;
    }

    private void count(long n) {
        read += n;
        if (read > limit) throw new HttpFailure(Status.CONTENT_TOO_LARGE, s"the body is larger than $limit bytes");
    }
}
