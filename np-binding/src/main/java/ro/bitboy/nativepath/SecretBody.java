package ro.bitboy.nativepath;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles a request body from public text and {@link NativeSecret}s entirely in native memory,
 * so a secret never becomes a Java string. Typical use is a login or token request:
 *
 * <pre>{@code
 * try (NativeSecret body = SecretBody.builder()
 *         .text("{\"user\":\"bob\",\"password\":\"")
 *         .jsonString(password)                 // escaped natively: quotes, backslashes, control bytes
 *         .text("\"}")
 *         .build();
 *      NativePathRequest req = NativePathRequest.of("POST", url).secretBody(body)) {
 *     ...
 * }
 * }</pre>
 *
 * Use {@link Builder#urlEncoded} for {@code application/x-www-form-urlencoded} bodies and
 * {@link Builder#raw} only where the value needs no escaping (for example a base64 token). The text
 * parts are ordinary Java strings, so keep anything secret out of them.
 */
public final class SecretBody {

    private SecretBody() {}

    public static Builder builder() {
        return new Builder();
    }

    private enum Kind { TEXT, RAW, JSON, URL, BASE64 }

    private record Part(Kind kind, byte[] text, NativeSecret secret) {}

    public static final class Builder {
        private final List<Part> parts = new ArrayList<>();

        /** Public text, copied as UTF-8. */
        public Builder text(String text) {
            parts.add(new Part(Kind.TEXT, text.getBytes(StandardCharsets.UTF_8), null));
            return this;
        }

        /** The secret's bytes, unchanged. */
        public Builder raw(NativeSecret secret) {
            parts.add(new Part(Kind.RAW, null, secret));
            return this;
        }

        /** The secret escaped for use inside a JSON string (the surrounding quotes are yours to add). */
        public Builder jsonString(NativeSecret secret) {
            parts.add(new Part(Kind.JSON, null, secret));
            return this;
        }

        /** The secret percent-encoded for a URL query or form body. */
        public Builder urlEncoded(NativeSecret secret) {
            parts.add(new Part(Kind.URL, null, secret));
            return this;
        }

        /** The secret base64-encoded (standard alphabet, padded), for example for {@code Basic} credentials. */
        public Builder base64(NativeSecret secret) {
            parts.add(new Part(Kind.BASE64, null, secret));
            return this;
        }

        /**
         * Builds the body in a new native secret that refuses heap copies. Close it (and the input
         * secrets) when the request has been sent.
         */
        public NativeSecret build() {
            long total = 0;
            for (Part p : parts) total += size(p);
            NativeSecret out = NativeSecret.allocate(total, false);
            try {
                MemorySegment dst = out.asMemorySegment();
                long at = 0;
                for (Part p : parts) at = write(p, dst, at);
                return out;
            } catch (RuntimeException e) {
                out.close();
                throw e;
            }
        }
    }

    private static boolean unreserved(byte b) {
        return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9')
                || b == '-' || b == '.' || b == '_' || b == '~';
    }

    private static long size(Part p) {
        if (p.kind == Kind.TEXT) return p.text.length;
        MemorySegment s = p.secret.asMemorySegment();
        long n = p.secret.length();
        if (p.kind == Kind.RAW) return n;
        if (p.kind == Kind.BASE64) return (n + 2) / 3 * 4;
        long total = 0;
        for (long i = 0; i < n; i++) {
            byte b = s.get(ValueLayout.JAVA_BYTE, i);
            if (p.kind == Kind.JSON) total += (b == '"' || b == '\\') ? 2 : (b >= 0 && b < 0x20) ? 6 : 1;
            else total += unreserved(b) ? 1 : 3;
        }
        return total;
    }

    private static final byte[] HEX = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".getBytes(StandardCharsets.US_ASCII);

    private static long write(Part p, MemorySegment dst, long at) {
        if (p.kind == Kind.TEXT) {
            MemorySegment.copy(MemorySegment.ofArray(p.text), 0, dst, at, p.text.length);
            return at + p.text.length;
        }
        MemorySegment s = p.secret.asMemorySegment();
        long n = p.secret.length();
        if (p.kind == Kind.RAW) {
            if (n > 0) MemorySegment.copy(s, 0, dst, at, n);
            return at + n;
        }
        if (p.kind == Kind.BASE64) {
            for (long i = 0; i < n; i += 3) {
                int b0 = s.get(ValueLayout.JAVA_BYTE, i) & 0xFF;
                int b1 = i + 1 < n ? s.get(ValueLayout.JAVA_BYTE, i + 1) & 0xFF : 0;
                int b2 = i + 2 < n ? s.get(ValueLayout.JAVA_BYTE, i + 2) & 0xFF : 0;
                dst.set(ValueLayout.JAVA_BYTE, at++, B64[b0 >> 2]);
                dst.set(ValueLayout.JAVA_BYTE, at++, B64[((b0 & 3) << 4) | (b1 >> 4)]);
                dst.set(ValueLayout.JAVA_BYTE, at++, i + 1 < n ? B64[((b1 & 0xF) << 2) | (b2 >> 6)] : (byte) '=');
                dst.set(ValueLayout.JAVA_BYTE, at++, i + 2 < n ? B64[b2 & 0x3F] : (byte) '=');
            }
            return at;
        }
        for (long i = 0; i < n; i++) {
            byte b = s.get(ValueLayout.JAVA_BYTE, i);
            if (p.kind == Kind.JSON) {
                if (b == '"' || b == '\\') {
                    dst.set(ValueLayout.JAVA_BYTE, at++, (byte) '\\');
                    dst.set(ValueLayout.JAVA_BYTE, at++, b);
                } else if (b >= 0 && b < 0x20) {
                    // Written byte by byte: no temporary array derived from secret data.
                    dst.set(ValueLayout.JAVA_BYTE, at++, (byte) '\\');
                    dst.set(ValueLayout.JAVA_BYTE, at++, (byte) 'u');
                    dst.set(ValueLayout.JAVA_BYTE, at++, (byte) '0');
                    dst.set(ValueLayout.JAVA_BYTE, at++, (byte) '0');
                    dst.set(ValueLayout.JAVA_BYTE, at++, HEX[(b >> 4) & 0xF]);
                    dst.set(ValueLayout.JAVA_BYTE, at++, HEX[b & 0xF]);
                } else {
                    dst.set(ValueLayout.JAVA_BYTE, at++, b);
                }
            } else if (unreserved(b)) {
                dst.set(ValueLayout.JAVA_BYTE, at++, b);
            } else {
                dst.set(ValueLayout.JAVA_BYTE, at++, (byte) '%');
                dst.set(ValueLayout.JAVA_BYTE, at++, HEX[(b >> 4) & 0xF]);
                dst.set(ValueLayout.JAVA_BYTE, at++, HEX[b & 0xF]);
            }
        }
        return at;
    }
}
