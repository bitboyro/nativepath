package ro.bitboy.nativepath;

public final class NativePathResponse implements AutoCloseable {

    private final int statusCode;
    private final NativeSecret body;

    public NativePathResponse(int statusCode, NativeSecret body) {
        this.statusCode = statusCode;
        this.body = body;
    }

    public int statusCode() {
        return statusCode;
    }

    /**
     * Access the body as an isolated, volatile-wiped NativeSecret.
     */
    public NativeSecret bodyAsSecret() {
        return body;
    }

    /**
     * Convenience method to read as a String for non-sensitive data.
     */
    public String asString() {
        return body.toUtf8String();
    }

    @Override
    public void close() {
        if (body != null) {
            body.close();
        }
    }
}
