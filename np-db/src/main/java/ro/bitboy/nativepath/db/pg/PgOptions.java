package ro.bitboy.nativepath.db.pg;

import ro.bitboy.nativepath.NativeSecret;
import ro.bitboy.nativepath.SecretSource;
import ro.bitboy.nativepath.db.DbObserver;
import ro.bitboy.nativepath.db.jdbc.Unsupported;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Connection settings parsed from {@code jdbc:nativepath:postgresql://host[:port][/database][?key=value&...]}
 * and the {@link Properties} passed to the driver. URL parameters take precedence, as in pgjdbc.
 *
 * <p>The password is never read from here into a field: {@link #password()} returns the raw
 * property value ({@link NativeSecret}, {@link SecretSource}, {@code String} or {@code char[]}) and the
 * connection resolves it once, at authentication.
 */
public record PgOptions(String url, String host, int port, String database, String user,
                        Object password, String passwordFile, String applicationName,
                        int connectTimeoutMs, int socketTimeoutMs, Set<String> secretColumns,
                        boolean errorDetail, SslMode sslMode, String sslRootCert,
                        ChannelBinding channelBinding, DbObserver observer) {

    public static final String PREFIX = "jdbc:nativepath:postgresql:";

    /** libpq's {@code sslmode}; the ordinal is the value the native engine expects. */
    public enum SslMode {
        DISABLE("disable"), ALLOW("allow"), PREFER("prefer"), REQUIRE("require"),
        VERIFY_CA("verify-ca"), VERIFY_FULL("verify-full");

        final String name;

        SslMode(String name) {
            this.name = name;
        }
    }

    /** libpq's {@code channel_binding}; the ordinal is the value the native engine expects. */
    public enum ChannelBinding {
        DISABLE("disable"), PREFER("prefer"), REQUIRE("require");

        final String name;

        ChannelBinding(String name) {
            this.name = name;
        }
    }

    public static boolean accepts(String url) {
        return url != null && url.startsWith(PREFIX);
    }

    public static PgOptions parse(String url, Properties info) throws SQLException {
        if (!accepts(url)) throw new SQLException("not a NativePath PostgreSQL URL: " + url, "08001");
        String rest = url.substring(PREFIX.length());
        if (!rest.startsWith("//")) throw new SQLException("expected " + PREFIX + "//host[:port]/database", "08001");
        rest = rest.substring(2);

        Properties props = new Properties();
        if (info != null) props.putAll(info);
        int q = rest.indexOf('?');
        if (q >= 0) {
            for (String pair : rest.substring(q + 1).split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                String k = decode(eq < 0 ? pair : pair.substring(0, eq));
                String v = eq < 0 ? "" : decode(pair.substring(eq + 1));
                props.put(k, v);
            }
            rest = rest.substring(0, q);
        }

        int slash = rest.indexOf('/');
        String hostPort = slash < 0 ? rest : rest.substring(0, slash);
        String database = slash < 0 ? "" : decode(rest.substring(slash + 1));
        String host = hostPort.isEmpty() ? "localhost" : hostPort;
        int port = 5432;
        int colon = hostPort.lastIndexOf(':');
        if (colon > 0 && !hostPort.endsWith("]")) {
            host = hostPort.substring(0, colon);
            try {
                port = Integer.parseInt(hostPort.substring(colon + 1));
            } catch (NumberFormatException e) {
                throw new SQLException("invalid port in URL", "08001");
            }
        }
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        if (host.contains(",")) throw Unsupported.feature("Multiple hosts");

        SslMode sslMode = choice(SslMode.values(), m -> m.name, string(props, "sslmode", "prefer"), "sslmode");
        String sslRootCert = string(props, "sslrootcert", null);
        if (sslRootCert != null && sslRootCert.isEmpty()) sslRootCert = null;
        if ((sslMode == SslMode.VERIFY_CA || sslMode == SslMode.VERIFY_FULL) && sslRootCert == null) {
            throw new SQLException("sslmode=" + sslMode.name + " needs sslrootcert: the PEM file with the CA that signed the server certificate", "08001");
        }
        if ("system".equals(sslRootCert)) throw Unsupported.feature("sslrootcert=system (the operating system's trust store)");
        ChannelBinding channelBinding = choice(ChannelBinding.values(), c -> c.name, string(props, "channelBinding", "prefer"), "channelBinding");
        if (string(props, "sslcert", null) != null || string(props, "sslkey", null) != null) {
            throw Unsupported.feature("Client certificates (sslcert/sslkey)");
        }

        String user = string(props, "user", null);
        if (user == null || user.isEmpty()) throw new SQLException("no user given (set 'user')", "28000");
        if (database.isEmpty()) database = user;

        Set<String> secretColumns = Arrays.stream(string(props, "secretColumns", "").split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                // "table.column" protects column "column" in every table: the server reports only a
                // table OID per column, so the table part cannot be checked yet. Over-matching is the safe side.
                .map(s -> s.substring(s.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());

        String safeUrl = url.replaceAll("([?&])password=[^&]*", "$1password=***");
        return new PgOptions(safeUrl, host, port, database, user,
                props.get("password"), string(props, "passwordFile", null),
                string(props, "applicationName", "NativePath"),
                seconds(props, "connectTimeout", 10), seconds(props, "socketTimeout", 0),
                secretColumns, Boolean.parseBoolean(string(props, "errorDetail", "false")),
                sslMode, sslRootCert, channelBinding, observer(props));
    }

    private static DbObserver observer(Properties p) throws SQLException {
        Object o = p.get("observer");
        if (o == null || o instanceof DbObserver) return (DbObserver) o;
        throw new SQLException("observer must be a " + DbObserver.class.getName(), "08001");
    }

    private static <E> E choice(E[] values, java.util.function.Function<E, String> name, String value, String key) throws SQLException {
        String v = value.strip().toLowerCase(Locale.ROOT);
        for (E e : values) {
            if (name.apply(e).equals(v)) return e;
        }
        throw new SQLException("invalid " + key + ": " + value, "08001");
    }

    private static String string(Properties p, String key, String def) {
        Object v = p.get(key);
        return v == null ? def : v.toString();
    }

    private static int seconds(Properties p, String key, int def) throws SQLException {
        String v = string(p, key, null);
        if (v == null || v.isEmpty()) return def * 1000;
        try {
            return Math.multiplyExact(Integer.parseInt(v.strip()), 1000);
        } catch (NumberFormatException | ArithmeticException e) {
            throw new SQLException("invalid " + key + ": " + v, "08001");
        }
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        // Never print the password.
        return "PgOptions[" + host + ":" + port + "/" + database + ", user=" + user + "]";
    }
}
