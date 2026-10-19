package ro.bitboy.nativepath.spring.server;

import ro.bitboy.nativepath.ServerExchange;
import org.springframework.util.MultiValueMap;

import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Request headers for Spring, read from the exchange's native header block on demand: a lookup
 * such as {@code getFirst("Content-Type")} scans the block and creates a string only for the header
 * asked for. Headers nobody reads never become Java strings. Iterating builds the full map once.
 * Case-insensitive; read-only (Spring wraps request headers read-only anyway).
 */
final class NativeRequestHeaders extends AbstractMap<String, List<String>> implements MultiValueMap<String, String> {

    private static final List<String> ABSENT = List.of();

    private final ServerExchange exchange;
    // Spring looks up some headers (Content-Type, Accept) several times per request: decode each once.
    private final Map<String, List<String>> looked = new java.util.HashMap<>(8);

    NativeRequestHeaders(ServerExchange exchange) {
        this.exchange = exchange;
    }

    @Override
    public List<String> get(Object key) {
        if (!(key instanceof String name)) {
            return null;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        List<String> v = looked.get(lower);
        if (v == null) {
            v = exchange.headerValues(lower);
            looked.put(lower, v == null ? ABSENT : v);
        }
        return v == ABSENT ? null : v;
    }

    @Override
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    @Override
    public String getFirst(String key) {
        List<String> v = get(key);
        return v == null ? null : v.get(0);
    }

    @Override
    public Set<Entry<String, List<String>>> entrySet() {
        return exchange.headers().entrySet();
    }

    @Override
    public Map<String, String> toSingleValueMap() {
        Map<String, String> out = new LinkedHashMap<>();
        exchange.headers().forEach((k, v) -> out.put(k, v.get(0)));
        return out;
    }

    @Override
    public void add(String key, String value) {
        throw new UnsupportedOperationException("request headers are read-only");
    }

    @Override
    public void addAll(String key, List<? extends String> values) {
        throw new UnsupportedOperationException("request headers are read-only");
    }

    @Override
    public void addAll(MultiValueMap<String, String> values) {
        throw new UnsupportedOperationException("request headers are read-only");
    }

    @Override
    public void set(String key, String value) {
        throw new UnsupportedOperationException("request headers are read-only");
    }

    @Override
    public void setAll(Map<String, String> values) {
        throw new UnsupportedOperationException("request headers are read-only");
    }
}
