package ro.bitboy.nativepath.spring;

import ro.bitboy.nativepath.NativePathResponse;
import ro.bitboy.nativepath.NativeSecret;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A Spring {@link ClientHttpResponse} backed by an NativePath native response.
 *
 * <p>The response body is read once from off-heap native memory into a
 * {@code byte[]} and then handed to Spring's normal deserialization pipeline
 * (Jackson, etc.). The off-heap {@link NativeSecret} is released and zeroed
 * immediately after that single copy.
 *
 * <p>For fully zero-copy, secret-safe consumption of the body, use
 * {@link #bodyAsSecret()} instead of relying on Spring's {@code getBody()}.
 */
public class NativePathClientHttpResponse implements ClientHttpResponse {

    private final NativePathResponse response;
    private final HttpHeaders headers = new HttpHeaders();
    private InputStream bodyStream;

    public NativePathClientHttpResponse(NativePathResponse response) {
        this.response = response;
    }

    @Override
    public HttpStatusCode getStatusCode() throws IOException {
        return HttpStatusCode.valueOf(response.statusCode());
    }

    @Override
    public String getStatusText() throws IOException {
        return HttpStatusCode.valueOf(response.statusCode()).toString();
    }

    @Override
    public HttpHeaders getHeaders() {
        return headers;
    }

    /**
     * Standard Spring body accessor. Copies the off-heap body into a heap
     * {@code byte[]} once, then releases and zeroes the native buffer immediately.
     * Subsequent calls return the same cached {@code InputStream}.
     */
    @Override
    public InputStream getBody() throws IOException {
        if (bodyStream == null) {
            // One controlled copy to heap — native buffer wiped right after
            try (NativeSecret secret = response.bodyAsSecret()) {
                byte[] bytes = secret.toByteArray();
                bodyStream = new ByteArrayInputStream(bytes);
            }
        }
        return bodyStream;
    }

    /**
     * Zero-copy, secure accessor. Returns the raw off-heap {@link NativeSecret}.
     * The caller is responsible for closing it, which triggers volatile zeroing.
     * Never turns the body into a JVM String or heap byte[].
     */
    public NativeSecret bodyAsSecret() {
        return response.bodyAsSecret();
    }

    @Override
    public void close() {
        response.close();
    }
}
