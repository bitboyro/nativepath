package ro.bitboy.nativepath.db;

import ro.bitboy.nativepath.NativeSecret;
import ro.bitboy.nativepath.SecretSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HikariCP + Spring {@code JdbcClient} and transactions over the NativePath driver. */
class SpringJdbcClientTest {

    record Order(long id, String customer, BigDecimal total, LocalDateTime created) {}

    private static HikariDataSource pool;
    private static JdbcClient jdbc;

    @BeforeAll
    static void start() {
        NativePathDataSource ds = new NativePathDataSource();
        ds.setUrl(PostgresDriverTest.url(""));
        ds.setUser("np");
        ds.setPasswordSource(SecretSource.file(PostgresDriverTest.dir().resolve("password.txt")));
        ds.setSecretColumns("card");
        HikariConfig cfg = new HikariConfig();
        cfg.setDataSource(ds);
        cfg.setMaximumPoolSize(4);
        cfg.setPoolName("np-test");
        pool = new HikariDataSource(cfg);
        jdbc = JdbcClient.create(pool);
        jdbc.sql("DROP TABLE IF EXISTS np_orders").update();
        jdbc.sql("CREATE TABLE np_orders (id bigserial primary key, customer text not null, total numeric(10,2), "
                + "created timestamp not null default now(), card text)").update();
    }

    @AfterAll
    static void stop() {
        if (pool != null) {
            jdbc.sql("DROP TABLE IF EXISTS np_orders").update();
            pool.close();
        }
    }

    @Test
    void crudThroughJdbcClient() {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO np_orders(customer, total) VALUES (:c, :t)")
                .param("c", "acme").param("t", new BigDecimal("19.99"))
                .update(keys, "id");
        long id = keys.getKey().longValue();

        Order o = jdbc.sql("SELECT id, customer, total, created FROM np_orders WHERE id = ?")
                .param(id).query(Order.class).single();
        assertEquals("acme", o.customer());
        assertEquals(new BigDecimal("19.99"), o.total());

        jdbc.sql("INSERT INTO np_orders(customer, total) VALUES (?, ?)").params("globex", null).update();
        List<Map<String, Object>> rows = jdbc.sql("SELECT customer, total FROM np_orders ORDER BY id").query().listOfRows();
        assertEquals(2, rows.size());
        assertEquals(null, rows.get(1).get("total"));

        long count = jdbc.sql("SELECT count(*) FROM np_orders").query(Long.class).single();
        assertEquals(2, count);
        assertEquals(1, jdbc.sql("DELETE FROM np_orders WHERE customer = :c").param("c", "globex").update());
    }

    @Test
    void springTransactionsCommitAndRollBack() {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(pool));
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(s -> {
            jdbc.sql("INSERT INTO np_orders(customer) VALUES ('tx-rolled-back')").update();
            throw new IllegalStateException("boom");
        }));
        tx.executeWithoutResult(s -> jdbc.sql("INSERT INTO np_orders(customer) VALUES ('tx-committed')").update());
        List<String> names = jdbc.sql("SELECT customer FROM np_orders WHERE customer LIKE 'tx-%'").query(String.class).list();
        assertEquals(List.of("tx-committed"), names);
    }

    @Test
    void secretColumnThroughJdbcClient() throws Exception {
        try (NativeSecret card = NativeSecret.fromFile(PostgresDriverTest.dir().resolve("card.txt"))) {
            jdbc.sql("INSERT INTO np_orders(customer, card) VALUES (?, ?)").params("secret-buyer", card).update();
        }
        // A plain String mapping must fail: the column is secret.
        assertThrows(Exception.class, () -> jdbc.sql("SELECT card FROM np_orders WHERE customer = 'secret-buyer'")
                .query(String.class).single());
        // Reading it as a NativeSecret keeps it native.
        try (NativeSecret s = jdbc.sql("SELECT card FROM np_orders WHERE customer = 'secret-buyer'")
                .query((rs, n) -> rs.getObject(1, NativeSecret.class)).single();
             NativeSecret expected = NativeSecret.fromFile(PostgresDriverTest.dir().resolve("card.txt"))) {
            assertEquals(-1, s.asMemorySegment().mismatch(expected.asMemorySegment()));
            assertFalse(s.heapCopiesAllowed());
        }
        assertTrue(pool.getHikariPoolMXBean() == null || pool.getHikariPoolMXBean().getTotalConnections() >= 0);
    }
}
