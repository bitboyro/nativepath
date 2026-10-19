package ro.bitboy.nativepath.spring;

import ro.bitboy.nativepath.NativeSecret;
import ro.bitboy.nativepath.SecretSource;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Named {@link SecretSource}s, configured under {@code nativepath.secrets.*}. The bean
 * holds locations only; values are read on demand into a {@link NativeSecret} that the caller
 * closes:
 *
 * <pre>{@code
 * try (NativeSecret pw = sources.load("db-password");
 *      NativeSecret body = SecretBody.builder().text("{\"password\":\"").jsonString(pw).text("\"}").build()) {
 *     ...
 * }
 * }</pre>
 *
 * Closing the bean releases a client it created for vault sources (Spring does this on shutdown).
 */
public final class SecretSources implements AutoCloseable {

    private final Map<String, SecretSource> sources;
    private final AutoCloseable owned;

    public SecretSources(Map<String, SecretSource> sources, AutoCloseable owned) {
        this.sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
        this.owned = owned;
    }

    public SecretSource get(String name) {
        SecretSource s = sources.get(name);
        if (s == null) {
            throw new IllegalArgumentException("no secret source '" + name + "' (configured: " + sources.keySet() + ")");
        }
        return s;
    }

    /** Loads the named secret into new native memory; the caller must close it. */
    public NativeSecret load(String name) throws IOException {
        return get(name).load();
    }

    public Set<String> names() {
        return sources.keySet();
    }

    @Override
    public void close() throws Exception {
        if (owned != null) owned.close();
    }
}
