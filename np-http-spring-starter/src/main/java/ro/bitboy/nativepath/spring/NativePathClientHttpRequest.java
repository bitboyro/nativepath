package ro.bitboy.nativepath.spring;

import ro.bitboy.nativepath.NativePathHttpClient;
import ro.bitboy.nativepath.NativePathRequest;
import ro.bitboy.nativepath.NativePathResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.Set;

/**
 * A Spring {@link ClientHttpRequest} backed by NativePath's native Rust engine.
 *
 * <p>Any method is supported and request headers are forwarded. Spring collects the request body
 * on the Java heap before it is sent; to keep a secret body off the heap, build an
 * {@link NativePathRequest} with {@code secretBody(NativeSecret)} and use {@link NativePathHttpClient}
 * directly.
 */
public class NativePathClientHttpRequest implements ClientHttpRequest {

    // Set by the HTTP layer from the actual body and connection.
    private static final Set<String> MANAGED_HEADERS = Set.of("content-length", "host", "connection", "transfer-encoding");

    private final NativePathHttpClient client;
    private final URI uri;
    private final HttpMethod method;
    private final HttpHeaders headers = new HttpHeaders();
    private final ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();

    private final boolean streaming;
    private final SecretPolicy policy;

    public NativePathClientHttpRequest(NativePathHttpClient client, URI uri, HttpMethod method, boolean streaming) {
        this(client, uri, method, streaming, SecretPolicy.DEFAULT);
    }

    public NativePathClientHttpRequest(NativePathHttpClient client, URI uri, HttpMethod method, boolean streaming,
                                    SecretPolicy policy) {
        this.policy = policy;
        this.client = client;
        this.streaming = streaming;
        this.uri = uri;
        this.method = method;
    }

    @Override
    public ClientHttpResponse execute() throws IOException {
        if (client.isSecretMode()) {
            policy.check(headers, bodyBuffer.size());
        }
        boolean plainGet = HttpMethod.GET.equals(method) && bodyBuffer.size() == 0 && headers.isEmpty();
        if (!streaming && plainGet) {
            NativePathResponse response = client.get(uri.toString());
            return new NativePathClientHttpResponse(response);
        }
        try (NativePathRequest request = NativePathRequest.of(method.name(), uri.toString())) {
            for (var entry : headers.entrySet()) {
                if (MANAGED_HEADERS.contains(entry.getKey().toLowerCase())) continue;
                for (String v : entry.getValue()) request.header(entry.getKey(), v);
            }
            if (bodyBuffer.size() > 0) {
                request.body(bodyBuffer.toByteArray());
            }
            return new NativePathStreamClientHttpResponse(client.getStream(request));
        }
    }

    @Override
    public HttpMethod getMethod() {
        return method;
    }

    @Override
    public URI getURI() {
        return uri;
    }

    @Override
    public HttpHeaders getHeaders() {
        return headers;
    }

    @Override
    public OutputStream getBody() throws IOException {
        return bodyBuffer;
    }
}
