package ro.bitboy.nativepath.db.pg;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PgSqlTest {

    private static String rw(String sql) throws SQLException {
        return PgSql.rewritePlaceholders(sql).sql();
    }

    @Test
    void placeholdersBecomePositional() throws SQLException {
        PgSql.Rewritten r = PgSql.rewritePlaceholders("SELECT * FROM t WHERE a = ? AND b IN (?, ?)");
        assertEquals("SELECT * FROM t WHERE a = $1 AND b IN ($2, $3)", r.sql());
        assertEquals(3, r.parameterCount());
    }

    @Test
    void questionMarksInsideLiteralsAndCommentsStay() throws SQLException {
        assertEquals("SELECT 'a?b', \"c?\", $1 -- why?\n, $2 /* x? /* nested? */ */",
                rw("SELECT 'a?b', \"c?\", ? -- why?\n, ? /* x? /* nested? */ */"));
        assertEquals("SELECT 'it''s?', $1", rw("SELECT 'it''s?', ?"));
        assertEquals("SELECT E'\\'?', $1", rw("SELECT E'\\'?', ?"));
        assertEquals("SELECT $tag$ ? $tag$, $$?$$, $1", rw("SELECT $tag$ ? $tag$, $$?$$, ?"));
    }

    @Test
    void doubledQuestionMarkIsTheOperator() throws SQLException {
        assertEquals("SELECT doc ? 'k' FROM t WHERE id = $1", rw("SELECT doc ?? 'k' FROM t WHERE id = ?"));
    }

    @Test
    void existingPositionalParametersAreLeftAlone() throws SQLException {
        assertEquals("SELECT $1::int", rw("SELECT $1::int"));
    }

    @Test
    void unterminatedQuotesAreErrors() {
        assertThrows(SQLException.class, () -> rw("SELECT 'oops"));
        assertThrows(SQLException.class, () -> rw("SELECT /* oops"));
        assertThrows(SQLException.class, () -> rw("SELECT $q$ oops"));
    }

    @Test
    void returningIsAddedOnlyToDml() {
        assertEquals("INSERT INTO t(a) VALUES ($1) RETURNING *", PgSql.withReturning("INSERT INTO t(a) VALUES ($1);", null));
        assertEquals("INSERT INTO t(a) VALUES ($1) RETURNING id, \"Mixed Case\"",
                PgSql.withReturning("INSERT INTO t(a) VALUES ($1)", new String[] {"id", "Mixed Case"}));
        assertEquals("INSERT INTO t(a) VALUES (1) RETURNING a", PgSql.withReturning("INSERT INTO t(a) VALUES (1) RETURNING a", null));
        assertEquals("SELECT 1", PgSql.withReturning("SELECT 1", null));
    }
}
