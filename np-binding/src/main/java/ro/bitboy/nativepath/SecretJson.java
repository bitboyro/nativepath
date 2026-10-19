package ro.bitboy.nativepath;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Splits a JSON object held in native memory into secret and public parts without putting the
 * secrets on the JVM heap.
 *
 * <p>The named top-level string fields are copied native to native into their own
 * {@link NativeSecret}s. Everything else is returned as a heap {@code byte[]} with those values
 * emptied (for example {@code "access_token":""}), which an ordinary JSON mapper can turn into
 * a normal object. Anything you do not name here is treated as public data.
 *
 * <p>Limits: top-level fields only; a secret field must be a JSON string without escape
 * sequences (tokens, keys and passwords normally are); numbers, nested objects and arrays cannot
 * be extracted as secrets. Keys are compared after decoding escapes, the way a JSON mapper reads
 * them, and a secret key that appears twice is rejected: either case could otherwise leave a
 * secret value in the public part.
 */
public final class SecretJson {

    private SecretJson() {}

    /** Result of {@link #extract}. Close it to wipe every extracted secret. */
    public record Extracted(Map<String, NativeSecret> secrets, byte[] publicJson) implements AutoCloseable {
        @Override
        public void close() {
            secrets.values().forEach(NativeSecret::close);
        }
    }

    public static Extracted extract(NativeSecret body, Set<String> secretKeys) {
        MemorySegment b = body.asMemorySegment();
        long n = b.byteSize();
        Map<String, byte[]> keyBytes = new HashMap<>();
        for (String k : secretKeys) keyBytes.put(k, k.getBytes(StandardCharsets.UTF_8));

        Map<String, long[]> spans = new HashMap<>();      // key -> {start, end} of the string contents
        long i = skipWs(b, n, 0);
        expect(b, n, i, '{');
        i++;
        while (true) {
            i = skipWs(b, n, i);
            if (at(b, n, i) == '}') break;
            expect(b, n, i, '"');
            long keyStart = ++i;
            boolean keyEscaped = false;
            while (at(b, n, i) != '"') {
                if (at(b, n, i) == '\\') { keyEscaped = true; i++; }
                i++;
            }
            long keyEnd = i++;
            String matched = matchKey(b, keyStart, keyEnd, keyEscaped, keyBytes);
            i = skipWs(b, n, i);
            expect(b, n, i, ':');
            i = skipWs(b, n, i + 1);
            if (matched != null) {
                if (spans.containsKey(matched)) {
                    throw new IllegalArgumentException("secret field '" + matched + "' appears more than once");
                }
                expect(b, n, i, '"');
                long vs = ++i;
                while (at(b, n, i) != '"') {
                    if (at(b, n, i) == '\\') {
                        throw new UnsupportedOperationException("escape sequences in secret field '" + matched + "' are not supported");
                    }
                    i++;
                }
                spans.put(matched, new long[] { vs, i });
                i++;
            } else {
                i = skipValue(b, n, i);
            }
            i = skipWs(b, n, i);
            if (at(b, n, i) == ',') { i++; continue; }
            expect(b, n, i, '}');
            break;
        }

        Map<String, NativeSecret> secrets = new HashMap<>();
        try {
            for (Map.Entry<String, long[]> e : spans.entrySet()) {
                long len = e.getValue()[1] - e.getValue()[0];
                NativeSecret s = NativeSecret.allocate(len, body.heapCopiesAllowed());
                if (len > 0) MemorySegment.copy(b, e.getValue()[0], s.asMemorySegment(), 0, len);
                secrets.put(e.getKey(), s);
            }
        } catch (RuntimeException ex) {
            secrets.values().forEach(NativeSecret::close);
            throw ex;
        }

        // Public JSON: the body with each secret value removed.
        long removed = spans.values().stream().mapToLong(sp -> sp[1] - sp[0]).sum();
        byte[] out = new byte[(int) (n - removed)];
        long[][] sorted = spans.values().stream().sorted((x, y) -> Long.compare(x[0], y[0])).toArray(long[][]::new);
        long src = 0;
        int dst = 0;
        for (long[] sp : sorted) {
            int len = (int) (sp[0] - src);
            MemorySegment.copy(b, src, MemorySegment.ofArray(out), dst, len);
            dst += len;
            src = sp[1];
        }
        MemorySegment.copy(b, src, MemorySegment.ofArray(out), dst, n - src);
        return new Extracted(secrets, out);
    }

    /**
     * Copies the string found under a path of nested object keys (for example
     * {@code "data", "data", "password"} in a Vault KV v2 response) into a new {@link NativeSecret}.
     * JSON escapes ({@code \"}, {@code \n}, {@code <}, surrogate pairs) are decoded native to
     * native. Nothing else in the document is copied. Errors name the missing key, never a value.
     */
    public static NativeSecret extractAt(NativeSecret body, String... path) {
        if (path.length == 0) throw new IllegalArgumentException("empty JSON path");
        MemorySegment b = body.asMemorySegment();
        long n = b.byteSize();
        long i = skipWs(b, n, 0);
        for (int d = 0; d < path.length; d++) {
            if (n == 0 || at(b, n, i) != '{') {
                throw new IllegalArgumentException("'" + String.join(".", java.util.Arrays.copyOf(path, d)) + "' is not a JSON object");
            }
            i = findMember(b, n, i, Map.of(path[d], path[d].getBytes(StandardCharsets.UTF_8)));
            if (i < 0) {
                throw new IllegalArgumentException("no field '" + String.join(".", java.util.Arrays.copyOf(path, d + 1)) + "' in the JSON document");
            }
        }
        if (at(b, n, i) != '"') {
            throw new IllegalArgumentException("field '" + String.join(".", path) + "' is not a JSON string");
        }
        long start = i + 1, end = skipString(b, n, i) - 1;
        NativeSecret s = NativeSecret.allocate(unescape(b, start, end, null), body.heapCopiesAllowed());
        try {
            unescape(b, start, end, s.asMemorySegment());
            return s;
        } catch (RuntimeException e) {
            s.close();
            throw e;
        }
    }

    /** Offset of the value of member {@code key} in the object starting at {@code i}, or -1. */
    private static long findMember(MemorySegment b, long n, long i, Map<String, byte[]> key) {
        i++;
        while (true) {
            i = skipWs(b, n, i);
            if (at(b, n, i) == '}') return -1;
            expect(b, n, i, '"');
            long keyStart = ++i;
            boolean keyEscaped = false;
            while (at(b, n, i) != '"') {
                if (at(b, n, i) == '\\') { keyEscaped = true; i++; }
                i++;
            }
            long keyEnd = i++;
            boolean match = matchKey(b, keyStart, keyEnd, keyEscaped, key) != null;
            i = skipWs(b, n, i);
            expect(b, n, i, ':');
            i = skipWs(b, n, i + 1);
            if (match) return i;
            i = skipWs(b, n, skipValue(b, n, i));
            if (at(b, n, i) == ',') { i++; continue; }
            expect(b, n, i, '}');
            return -1;
        }
    }

    /**
     * Decodes the JSON string contents {@code [from, to)} into {@code dst} (or only measures them
     * when {@code dst} is null) and returns the decoded length. Works byte by byte on native
     * memory: no array or string is built from the value.
     */
    private static long unescape(MemorySegment b, long from, long to, MemorySegment dst) {
        long out = 0;
        for (long i = from; i < to; i++) {
            byte c = b.get(ValueLayout.JAVA_BYTE, i);
            if (c != '\\') {
                if (dst != null) dst.set(ValueLayout.JAVA_BYTE, out, c);
                out++;
                continue;
            }
            if (++i >= to) throw new IllegalArgumentException("invalid escape in secret JSON string");
            byte e = b.get(ValueLayout.JAVA_BYTE, i);
            int cp;
            switch (e) {
                case '"', '\\', '/' -> cp = e;
                case 'b' -> cp = '\b';
                case 'f' -> cp = '\f';
                case 'n' -> cp = '\n';
                case 'r' -> cp = '\r';
                case 't' -> cp = '\t';
                case 'u' -> {
                    cp = hex4(b, i + 1, to);
                    i += 4;
                    if (cp >= 0xD800 && cp <= 0xDBFF) {
                        int lo = i + 6 < to && b.get(ValueLayout.JAVA_BYTE, i + 1) == '\\'
                                && b.get(ValueLayout.JAVA_BYTE, i + 2) == 'u' ? hex4(b, i + 3, to) : -1;
                        if (lo < 0xDC00 || lo > 0xDFFF) throw new IllegalArgumentException("unpaired surrogate in secret JSON string");
                        cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                        i += 6;
                    } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
                        throw new IllegalArgumentException("unpaired surrogate in secret JSON string");
                    }
                }
                default -> throw new IllegalArgumentException("invalid escape in secret JSON string");
            }
            out = putUtf8(dst, out, cp);
        }
        return out;
    }

    private static int hex4(MemorySegment b, long i, long to) {
        if (i + 4 > to) throw new IllegalArgumentException("invalid \\u escape in secret JSON string");
        int v = 0;
        for (long k = i; k < i + 4; k++) {
            int c = b.get(ValueLayout.JAVA_BYTE, k);
            int d = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'f' ? c - 'a' + 10 : c >= 'A' && c <= 'F' ? c - 'A' + 10 : -1;
            if (d < 0) throw new IllegalArgumentException("invalid \\u escape in secret JSON string");
            v = (v << 4) | d;
        }
        return v;
    }

    private static long putUtf8(MemorySegment dst, long at, int cp) {
        int n = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
        if (dst != null) {
            if (n == 1) {
                dst.set(ValueLayout.JAVA_BYTE, at, (byte) cp);
            } else {
                dst.set(ValueLayout.JAVA_BYTE, at, (byte) ((n == 2 ? 0xC0 : n == 3 ? 0xE0 : 0xF0) | (cp >> (6 * (n - 1)))));
                for (int k = 1; k < n; k++) {
                    dst.set(ValueLayout.JAVA_BYTE, at + k, (byte) (0x80 | ((cp >> (6 * (n - 1 - k))) & 0x3F)));
                }
            }
        }
        return at + n;
    }

    private static byte at(MemorySegment b, long n, long i) {
        if (i >= n) throw new IllegalArgumentException("unexpected end of JSON");
        return b.get(ValueLayout.JAVA_BYTE, i);
    }

    private static void expect(MemorySegment b, long n, long i, char c) {
        if (at(b, n, i) != c) throw new IllegalArgumentException("invalid JSON: expected '" + c + "' at offset " + i);
    }

    private static long skipWs(MemorySegment b, long n, long i) {
        while (i < n) {
            byte c = b.get(ValueLayout.JAVA_BYTE, i);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') break;
            i++;
        }
        return i;
    }

    /** Like {@link #matchKey(MemorySegment, long, long, Map)}; an escaped key (public text) is decoded first. */
    private static String matchKey(MemorySegment b, long s, long e, boolean escaped, Map<String, byte[]> keys) {
        if (!escaped) return matchKey(b, s, e, keys);
        try (Arena a = Arena.ofConfined()) {
            MemorySegment k = a.allocate(Math.max(unescape(b, s, e, null), 1));
            return matchKey(k, 0, unescape(b, s, e, k), keys);
        }
    }

    private static String matchKey(MemorySegment b, long s, long e, Map<String, byte[]> keys) {
        for (Map.Entry<String, byte[]> k : keys.entrySet()) {
            byte[] kb = k.getValue();
            if (kb.length != e - s) continue;
            boolean same = true;
            for (int j = 0; j < kb.length && same; j++) same = b.get(ValueLayout.JAVA_BYTE, s + j) == kb[j];
            if (same) return k.getKey();
        }
        return null;
    }

    /** Skips one JSON value (string, number, literal, object or array) and returns the offset after it. */
    private static long skipValue(MemorySegment b, long n, long i) {
        byte c = at(b, n, i);
        if (c == '"') return skipString(b, n, i);
        if (c == '{' || c == '[') {
            int depth = 0;
            while (true) {
                byte d = at(b, n, i);
                if (d == '"') { i = skipString(b, n, i); continue; }
                if (d == '{' || d == '[') depth++;
                if (d == '}' || d == ']') depth--;
                i++;
                if (depth == 0) return i;
            }
        }
        while (i < n) {
            byte d = b.get(ValueLayout.JAVA_BYTE, i);
            if (d == ',' || d == '}' || d == ']' || d == ' ' || d == '\n' || d == '\r' || d == '\t') break;
            i++;
        }
        return i;
    }

    private static long skipString(MemorySegment b, long n, long i) {
        i++;
        while (at(b, n, i) != '"') {
            if (at(b, n, i) == '\\') i++;
            i++;
        }
        return i + 1;
    }
}
