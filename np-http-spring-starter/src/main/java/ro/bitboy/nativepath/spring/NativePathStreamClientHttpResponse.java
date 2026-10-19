package ro.bitboy.nativepath.spring;

import ro.bitboy.nativepath.NativePathStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.io.InputStream;

/**
 * A Spring {@link ClientHttpResponse} whose body is read incrementally from native memory.
 *
 * <p>{@link #getBody()} returns the {@link NativePathStream} itself: nothing is buffered, so memory
 * use is flat for any body size, and each network chunk is wiped as the next one is read.
 * Closing the response (Spring does this after {@code retrieve()}) closes the stream.
 * Use {@link #stream()} for zero-copy access via {@link NativePathStream#nextChunk()}.
 */
public class NativePathStreamClientHttpResponse implements ClientHttpResponse {

    private final NativePathStream stream;
    private final HttpHeaders headers = new HttpHeaders();

    public NativePathStreamClientHttpResponse(NativePathStream stream) {
        this.stream = stream;
        // In secret mode header values stay native; only Content-Length (a number) is surfaced.
        if (!stream.isSecretMode()) {
            stream.headers().forEach(headers::addAll);
        }
        if (stream.contentLength() >= 0) {
            // Lets Spring's byte[] converter allocate the exact size instead of growing a buffer.
            headers.setContentLength(stream.contentLength());
        }
    }

    @Override
    public HttpStatusCode getStatusCode() throws IOException {
        return HttpStatusCode.valueOf(stream.statusCode());
    }

    @Override
    public String getStatusText() throws IOException {
        return HttpStatusCode.valueOf(stream.statusCode()).toString();
    }

    @Override
    public HttpHeaders getHeaders() {
        return headers;
    }

    @Override
    public InputStream getBody() throws IOException {
        return stream;
    }

    public NativePathStream stream() {
        return stream;
    }

    @Override
    public void close() {
        stream.close();
    }
}
