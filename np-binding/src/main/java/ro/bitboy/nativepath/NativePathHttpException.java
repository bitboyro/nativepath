package ro.bitboy.nativepath;

import java.io.IOException;

/** A response with a non-2xx status where the caller asked for the body. The message has no body data. */
public final class NativePathHttpException extends IOException {

    private final int statusCode;

    public NativePathHttpException(int statusCode, String method, String url) {
        super("HTTP " + statusCode + " from " + method + " " + url);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
