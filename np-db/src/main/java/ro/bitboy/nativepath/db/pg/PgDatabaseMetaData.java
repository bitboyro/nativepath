package ro.bitboy.nativepath.db.pg;

import ro.bitboy.nativepath.db.NativePathDriver;
import ro.bitboy.nativepath.db.jdbc.BaseDatabaseMetaData;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.RowIdLifetime;
import java.sql.SQLException;

/**
 * The database facts pools and Spring ask for (product, versions, transactions, batches).
 * Catalog queries (tables, columns, keys) are not implemented yet; ORMs need them.
 */
final class PgDatabaseMetaData extends BaseDatabaseMetaData {

    private final PgConnection connection;

    PgDatabaseMetaData(PgConnection connection) {
        this.connection = connection;
    }

    private String serverVersion() {
        String v = connection.serverParameter("server_version");
        return v == null ? "" : v;
    }

    private int versionPart(int i) {
        String[] parts = serverVersion().split("[^0-9]+");
        try {
            return parts.length > i ? Integer.parseInt(parts[i]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override public Connection getConnection() { return connection; }
    @Override public String getURL() { return connection.options().url(); }
    @Override public String getUserName() { return connection.options().user(); }
    @Override public boolean isReadOnly() throws SQLException { return connection.isReadOnly(); }

    @Override public String getDatabaseProductName() { return "PostgreSQL"; }
    @Override public String getDatabaseProductVersion() { return serverVersion(); }
    @Override public int getDatabaseMajorVersion() { return versionPart(0); }
    @Override public int getDatabaseMinorVersion() { return versionPart(1); }
    @Override public String getDriverName() { return "NativePath PostgreSQL"; }
    @Override public String getDriverVersion() { return NativePathDriver.MAJOR + "." + NativePathDriver.MINOR; }
    @Override public int getDriverMajorVersion() { return NativePathDriver.MAJOR; }
    @Override public int getDriverMinorVersion() { return NativePathDriver.MINOR; }
    @Override public int getJDBCMajorVersion() { return 4; }
    @Override public int getJDBCMinorVersion() { return 3; }
    @Override public int getSQLStateType() { return sqlStateSQL; }

    @Override public boolean supportsTransactions() { return true; }
    @Override public int getDefaultTransactionIsolation() { return Connection.TRANSACTION_READ_COMMITTED; }
    @Override public boolean supportsTransactionIsolationLevel(int level) { return level != Connection.TRANSACTION_NONE; }
    @Override public boolean supportsDataDefinitionAndDataManipulationTransactions() { return true; }
    @Override public boolean supportsDataManipulationTransactionsOnly() { return false; }
    @Override public boolean dataDefinitionCausesTransactionCommit() { return false; }
    @Override public boolean dataDefinitionIgnoredInTransactions() { return false; }
    @Override public boolean supportsSavepoints() { return false; }
    @Override public boolean supportsBatchUpdates() { return true; }
    @Override public boolean supportsGetGeneratedKeys() { return true; }
    @Override public boolean supportsMultipleResultSets() { return true; }
    @Override public boolean supportsMultipleTransactions() { return true; }
    @Override public boolean supportsResultSetType(int type) { return type == ResultSet.TYPE_FORWARD_ONLY; }
    @Override public boolean supportsResultSetConcurrency(int type, int concurrency) {
        return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY;
    }
    @Override public boolean supportsResultSetHoldability(int h) { return true; }
    @Override public int getResultSetHoldability() { return ResultSet.CLOSE_CURSORS_AT_COMMIT; }
    @Override public boolean supportsStoredProcedures() { return false; }
    @Override public boolean supportsNamedParameters() { return false; }
    @Override public boolean supportsStatementPooling() { return false; }
    @Override public RowIdLifetime getRowIdLifetime() { return RowIdLifetime.ROWID_UNSUPPORTED; }

    @Override public String getIdentifierQuoteString() { return "\""; }
    @Override public String getSearchStringEscape() { return "\\"; }
    @Override public String getExtraNameCharacters() { return ""; }
    @Override public boolean storesLowerCaseIdentifiers() { return true; }
    @Override public boolean storesUpperCaseIdentifiers() { return false; }
    @Override public boolean storesMixedCaseIdentifiers() { return false; }
    @Override public boolean storesMixedCaseQuotedIdentifiers() { return false; }
    @Override public boolean supportsMixedCaseIdentifiers() { return false; }
    @Override public boolean supportsMixedCaseQuotedIdentifiers() { return true; }
    @Override public boolean nullsAreSortedHigh() { return true; }
    @Override public boolean nullsAreSortedLow() { return false; }
    @Override public boolean nullsAreSortedAtStart() { return false; }
    @Override public boolean nullsAreSortedAtEnd() { return false; }
    @Override public boolean usesLocalFiles() { return false; }
    @Override public boolean usesLocalFilePerTable() { return false; }
    @Override public boolean supportsAlterTableWithAddColumn() { return true; }
    @Override public boolean supportsAlterTableWithDropColumn() { return true; }
    @Override public boolean supportsColumnAliasing() { return true; }
    @Override public boolean supportsGroupBy() { return true; }
    @Override public boolean supportsOrderByUnrelated() { return true; }
    @Override public boolean supportsLikeEscapeClause() { return true; }
    @Override public boolean supportsOuterJoins() { return true; }
    @Override public boolean supportsFullOuterJoins() { return true; }
    @Override public boolean supportsUnion() { return true; }
    @Override public boolean supportsUnionAll() { return true; }
    @Override public boolean supportsSubqueriesInExists() { return true; }
    @Override public boolean supportsSubqueriesInIns() { return true; }
    @Override public boolean supportsSchemasInDataManipulation() { return true; }
    @Override public boolean supportsSchemasInTableDefinitions() { return true; }
    @Override public boolean supportsCatalogsInDataManipulation() { return false; }
    @Override public boolean supportsCatalogsInTableDefinitions() { return false; }
    @Override public int getMaxConnections() { return 0; }
    @Override public boolean locatorsUpdateCopy() { return false; }
    @Override public boolean supportsStoredFunctionsUsingCallSyntax() { return false; }
    @Override public boolean autoCommitFailureClosesAllResultSets() { return false; }
    @Override public boolean generatedKeyAlwaysReturned() { return true; }

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
