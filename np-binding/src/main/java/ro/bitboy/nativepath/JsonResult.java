package ro.bitboy.nativepath;

import java.util.Map;

/**
 * A JSON response split by {@link NativePath.Call#fetchJson}: public data as an object, the named
 * secret fields as native memory. Close it to wipe the secrets.
 */
public record JsonResult<T>(int status, T value, Map<String, NativeSecret> secrets) implements AutoCloseable {

    public NativeSecret secret(String field) {
        NativeSecret s = secrets.get(field);
        if (s == null) throw new IllegalArgumentException("no secret field '" + field + "' in the response");
        return s;
    }

    @Override
    public void close() {
        secrets.values().forEach(NativeSecret::close);
    }
}
