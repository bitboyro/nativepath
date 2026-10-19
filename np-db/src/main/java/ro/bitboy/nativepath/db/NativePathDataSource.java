package ro.bitboy.nativepath.db;

import ro.bitboy.nativepath.SecretSource;
import ro.bitboy.nativepath.db.pg.PgConnection;
import ro.bitboy.nativepath.db.pg.PgOptions;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A non-pooling {@link DataSource}; give it to a pool such as HikariCP with
 * {@code hikariConfig.setDataSource(ds)}. With a {@link SecretSource} password, every new connection
 * loads the password fresh and wipes it right after authentication, so no long-lived copy exists,
 * even in native memory.
 */
public final class NativePathDataSource implements DataSource {

    private String url;
    private String user;
    private SecretSource passwordSource;
    private final Properties properties = new Properties();
    private int loginTimeoutSeconds;
    private DbObserver observer;

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUrl() {
        return url;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getUser() {
        return user;
    }

    public void setPasswordSource(SecretSource passwordSource) {
        this.passwordSource = passwordSource;
    }

    /** Path to a file holding the password (for example a mounted Kubernetes secret). */
    public void setPasswordFile(String path) {
        properties.setProperty("passwordFile", path);
    }

    /** Comma-separated column names whose values must stay off the JVM heap. */
    public void setSecretColumns(String columns) {
        properties.setProperty("secretColumns", columns);
    }

    public void setApplicationName(String name) {
        properties.setProperty("applicationName", name);
    }

    /** Receives connect and statement events, for example {@link ro.bitboy.nativepath.db.micrometer.NativePathDbMetrics}. */
    public void setObserver(DbObserver observer) {
        this.observer = observer;
    }

    /** Any other driver property (see {@link NativePathDriver}). */
    public void setProperty(String key, String value) {
        properties.setProperty(key, value);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Properties p = new Properties();
        p.putAll(properties);
        if (user != null) p.setProperty("user", user);
        if (passwordSource != null) p.put("password", passwordSource);
        if (observer != null) p.put("observer", observer);
        if (loginTimeoutSeconds > 0 && !p.containsKey("connectTimeout")) p.setProperty("connectTimeout", Integer.toString(loginTimeoutSeconds));
        return PgConnection.open(PgOptions.parse(url, p));
    }

    /** The password here is a heap {@code String}; prefer {@link #setPasswordSource}. */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Properties p = new Properties();
        p.putAll(properties);
        p.setProperty("user", username);
        p.put("password", password);
        if (observer != null) p.put("observer", observer);
        return PgConnection.open(PgOptions.parse(url, p));
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        // not used
    }

    @Override
    public void setLoginTimeout(int seconds) {
        loginTimeoutSeconds = seconds;
    }

    @Override
    public int getLoginTimeout() {
        return loginTimeoutSeconds;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("java.util.logging is not used");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("not a wrapper for " + iface.getName());
    }
}
