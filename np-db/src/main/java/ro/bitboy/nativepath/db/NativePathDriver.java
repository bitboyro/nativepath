package ro.bitboy.nativepath.db;

import ro.bitboy.nativepath.db.pg.PgConnection;
import ro.bitboy.nativepath.db.pg.PgOptions;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * JDBC driver for {@code jdbc:nativepath:postgresql://host[:port]/database}.
 *
 * <p>Properties (in the URL or the {@link Properties}; URL wins):
 * <ul>
 *   <li>{@code user}</li>
 *   <li>{@code password}: a {@link ro.bitboy.nativepath.NativeSecret} or {@link ro.bitboy.nativepath.SecretSource}
 *       (kept off the JVM heap), or a {@code String} / {@code char[]} (already on the heap)</li>
 *   <li>{@code passwordFile}: path to a file holding the password, read straight into native memory</li>
 *   <li>{@code secretColumns}: comma-separated column names whose values must stay off the heap</li>
 *   <li>{@code applicationName}, {@code connectTimeout} and {@code socketTimeout} (seconds)</li>
 *   <li>{@code errorDetail}: include the server's DETAIL in exceptions (default false: it often quotes row values)</li>
 *   <li>{@code sslmode}: {@code disable}, {@code allow}, {@code prefer} (default), {@code require},
 *       {@code verify-ca} or {@code verify-full}, as in libpq; {@code sslrootcert}: PEM file with the server's CA</li>
 *   <li>{@code channelBinding}: {@code disable}, {@code prefer} (default) or {@code require} (SCRAM-SHA-256-PLUS)</li>
 *   <li>{@code observer}: a {@link DbObserver} for metrics (a Properties object value)</li>
 * </ul>
 */
public final class NativePathDriver implements Driver {

    public static final int MAJOR = 0;
    public static final int MINOR = 1;

    static {
        try {
            DriverManager.registerDriver(new NativePathDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) return null; // JDBC: let other drivers try
        return PgConnection.open(PgOptions.parse(url, info));
    }

    @Override
    public boolean acceptsURL(String url) {
        return PgOptions.accepts(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return MAJOR;
    }

    @Override
    public int getMinorVersion() {
        return MINOR;
    }

    @Override
    public boolean jdbcCompliant() {
        return false; // a subset of JDBC, see docs/NP_DB.md
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("java.util.logging is not used");
    }
}
