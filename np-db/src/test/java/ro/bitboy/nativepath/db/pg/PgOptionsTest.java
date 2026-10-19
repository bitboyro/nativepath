package ro.bitboy.nativepath.db.pg;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgOptionsTest {

    @Test
    void parsesUrlAndProperties() throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", "fromProps");
        p.setProperty("applicationName", "props");
        PgOptions o = PgOptions.parse("jdbc:nativepath:postgresql://db.internal:6543/app?applicationName=url%20app"
                + "&secretColumns=customers.card_number,%20API_TOKEN&socketTimeout=7", p);
        assertEquals("db.internal", o.host());
        assertEquals(6543, o.port());
        assertEquals("app", o.database());
        assertEquals("fromProps", o.user());
        assertEquals("url app", o.applicationName());
        assertEquals(Set.of("card_number", "api_token"), o.secretColumns());
        assertEquals(7000, o.socketTimeoutMs());
        assertFalse(o.errorDetail());
    }

    @Test
    void defaults() throws SQLException {
        PgOptions o = PgOptions.parse("jdbc:nativepath:postgresql://?user=alice", null);
        assertEquals("localhost", o.host());
        assertEquals(5432, o.port());
        assertEquals("alice", o.database());
    }

    @Test
    void passwordNeverAppearsInTheStoredUrlOrToString() throws SQLException {
        PgOptions o = PgOptions.parse("jdbc:nativepath:postgresql://h/d?user=u&password=hunter2", null);
        assertFalse(o.url().contains("hunter2"));
        assertFalse(o.toString().contains("hunter2"));
        assertEquals("hunter2", o.password());
    }

    @Test
    void tlsOptions() throws SQLException {
        PgOptions o = PgOptions.parse("jdbc:nativepath:postgresql://h/d?user=u", null);
        assertEquals(PgOptions.SslMode.PREFER, o.sslMode(), "libpq's default");
        assertEquals(PgOptions.ChannelBinding.PREFER, o.channelBinding());
        o = PgOptions.parse("jdbc:nativepath:postgresql://h/d?user=u&sslmode=verify-full&sslrootcert=/etc/ca.pem&channelBinding=require", null);
        assertEquals(PgOptions.SslMode.VERIFY_FULL, o.sslMode());
        assertEquals("/etc/ca.pem", o.sslRootCert());
        assertEquals(PgOptions.ChannelBinding.REQUIRE, o.channelBinding());
        assertEquals(PgOptions.SslMode.REQUIRE, PgOptions.parse("jdbc:nativepath:postgresql://h/d?user=u&sslmode=REQUIRE", null).sslMode());
    }

    @Test
    void tlsMistakesFailAtParseTime() {
        SQLException e = assertThrows(SQLException.class,
                () -> PgOptions.parse("jdbc:nativepath:postgresql://h/d?user=u&sslmode=verify-full", null));
        assertTrue(e.getMessage().contains("sslrootcert"));
        assertThrows(SQLException.class, () -> PgOptions.parse("jdbc:nativepath:postgresql://h/d?user=u&sslmode=verify-ca", null));
        assertThrows(SQLException.class, () -> PgOptions.parse("jdbc:nativepath:postgresql://h/d?user=u&sslmode=always", null));
        assertThrows(SQLException.class, () -> PgOptions.parse("jdbc:nativepath:postgresql://h/d?user=u&channelBinding=maybe", null));
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> PgOptions.parse("jdbc:nativepath:postgresql://h/d?user=u&sslmode=require&sslcert=/c.pem", null));
        assertThrows(SQLFeatureNotSupportedException.class,
                () -> PgOptions.parse("jdbc:nativepath:postgresql://h/d?user=u&sslmode=verify-full&sslrootcert=system", null));
    }

    @Test
    void enumOrdinalsMatchTheNativeEngine() {
        // np_db_pg_connect takes these as integers (SslMode / ChannelBinding in np-db-postgres/src/tls.rs).
        assertEquals(0, PgOptions.SslMode.DISABLE.ordinal());
        assertEquals(1, PgOptions.SslMode.ALLOW.ordinal());
        assertEquals(2, PgOptions.SslMode.PREFER.ordinal());
        assertEquals(3, PgOptions.SslMode.REQUIRE.ordinal());
        assertEquals(4, PgOptions.SslMode.VERIFY_CA.ordinal());
        assertEquals(5, PgOptions.SslMode.VERIFY_FULL.ordinal());
        assertEquals(0, PgOptions.ChannelBinding.DISABLE.ordinal());
        assertEquals(1, PgOptions.ChannelBinding.PREFER.ordinal());
        assertEquals(2, PgOptions.ChannelBinding.REQUIRE.ordinal());
    }

    @Test
    void acceptsOnlyItsPrefix() {
        assertTrue(PgOptions.accepts("jdbc:nativepath:postgresql://h/d"));
        assertFalse(PgOptions.accepts("jdbc:postgresql://h/d"));
        assertThrows(SQLException.class, () -> PgOptions.parse("jdbc:nativepath:postgresql://h/d", null)); // no user
    }
}
