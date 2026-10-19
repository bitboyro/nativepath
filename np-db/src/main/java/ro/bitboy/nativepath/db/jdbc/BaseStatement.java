package ro.bitboy.nativepath.db.jdbc;

/**
 * Generated from {@code java.sql.Statement}: every method throws
 * {@link java.sql.SQLFeatureNotSupportedException}. Implementations override what they support,
 * so anything else fails loudly instead of silently doing nothing.
 */
public abstract class BaseStatement implements java.sql.Statement {

    @Override
    public void addBatch(java.lang.String p0) throws java.sql.SQLException {
        throw Unsupported.feature("Statement.addBatch");
    }

    @Override
    public void cancel() throws java.sql.SQLException {
        throw Unsupported.feature("Statement.cancel");
    }

    @Override
    public void clearBatch() throws java.sql.SQLException {
        throw Unsupported.feature("Statement.clearBatch");
    }

    @Override
    public void clearWarnings() throws java.sql.SQLException {
        throw Unsupported.feature("Statement.clearWarnings");
    }

    @Override
    public void close() throws java.sql.SQLException {
        throw Unsupported.feature("Statement.close");
    }

    @Override
    public void closeOnCompletion() throws java.sql.SQLException {
        throw Unsupported.feature("Statement.closeOnCompletion");
    }

    @Override
    public boolean execute(java.lang.String p0, int[] p1) throws java.sql.SQLException {
        throw Unsupported.feature("Statement.execute");
    }

    @Override
    public boolean execute(java.lang.String p0, java.lang.String[] p1) throws java.sql.SQLException {
        throw Unsupported.feature("Statement.execute");
    }

    @Override
    public boolean execute(java.lang.String p0, int p1) throws java.sql.SQLException {
        throw Unsupported.feature("Statement.execute");
    }

    @Override
    public boolean execute(java.lang.String p0) throws java.sql.SQLException {
        throw Unsupported.feature("Statement.execute");
    }

    @Override
    public int[] executeBatch() throws java.sql.SQLException {
        throw Unsupported.feature("Statement.executeBatch");
    }

    @Override
    public java.sql.ResultSet executeQuery(java.lang.String p0) throws java.sql.SQLException {
        throw Unsupported.feature("Statement.executeQuery");
    }

    @Override
    public int executeUpdate(java.lang.String p0, int[] p1) throws java.sql.SQLException {
        throw Unsupported.feature("Statement.executeUpdate");
    }

    @Override
    public int executeUpdate(java.lang.String p0, java.lang.String[] p1) throws java.sql.SQLException {
        throw Unsupported.feature("Statement.executeUpdate");
    }

    @Override
    public int executeUpdate(java.lang.String p0, int p1) throws java.sql.SQLException {
        throw Unsupported.feature("Statement.executeUpdate");
    }

    @Override
    public int executeUpdate(java.lang.String p0) throws java.sql.SQLException {
        throw Unsupported.feature("Statement.executeUpdate");
    }

    @Override
    public java.sql.Connection getConnection() throws java.sql.SQLException {
        throw Unsupported.feature("Statement.getConnection");
    }

    @Override
    public int getFetchDirection() throws java.sql.SQLException {
        throw Unsupported.feature("Statement.getFetchDirection");
    }

    @Override
    public int getFetchSize() throws java.sql.SQLException {
        throw Unsupported.feature("Statement.getFetchSize");
    }

    @Override
    public java.sql.ResultSet getGeneratedKeys() throws java.sql.SQLException {
        throw Unsupported.feature("Statement.getGeneratedKeys");
    }

    @Override
    public int getMaxFieldSize() throws java.sql.SQLException {
        throw Unsupported.feature("Statement.getMaxFieldSize");
    }

    @Override
    public int getMaxRows() throws java.sql.SQLException {
        throw Unsupported.feature("Statement.getMaxRows");
    }

    @Override
    public boolean getMoreResults() throws java.sql.SQLException {
        throw Unsupported.feature("Statement.getMoreResults");
    }

    @Override
    public boolean getMoreResults(int p0) throws java.sql.SQLException {
        throw Unsupported.feature("Statement.getMoreResults");
    }

    @Override
    public int getQueryTimeout() throws java.sql.SQLException {
        throw Unsupported.feature("Statement.getQueryTimeout");
    }

    @Override
    public java.sql.ResultSet getResultSet() throws java.sql.SQLException {
        throw Unsupported.feature("Statement.getResultSet");
    }

    @Override
    public int getResultSetConcurrency() throws java.sql.SQLException {
        throw Unsupported.feature("Statement.getResultSetConcurrency");
    }

    @Override
    public int getResultSetHoldability() throws java.sql.SQLException {
        throw Unsupported.feature("Statement.getResultSetHoldability");
    }

    @Override
    public int getResultSetType() throws java.sql.SQLException {
        throw Unsupported.feature("Statement.getResultSetType");
    }

    @Override
    public int getUpdateCount() throws java.sql.SQLException {
        throw Unsupported.feature("Statement.getUpdateCount");
    }

    @Override
    public java.sql.SQLWarning getWarnings() throws java.sql.SQLException {
        throw Unsupported.feature("Statement.getWarnings");
    }

    @Override
    public boolean isCloseOnCompletion() throws java.sql.SQLException {
        throw Unsupported.feature("Statement.isCloseOnCompletion");
    }

    @Override
    public boolean isClosed() throws java.sql.SQLException {
        throw Unsupported.feature("Statement.isClosed");
    }

    @Override
    public boolean isPoolable() throws java.sql.SQLException {
        throw Unsupported.feature("Statement.isPoolable");
    }

    @Override
    public boolean isWrapperFor(java.lang.Class<?> p0) throws java.sql.SQLException {
        throw Unsupported.feature("Statement.isWrapperFor");
    }

    @Override
    public void setCursorName(java.lang.String p0) throws java.sql.SQLException {
        throw Unsupported.feature("Statement.setCursorName");
    }

    @Override
    public void setEscapeProcessing(boolean p0) throws java.sql.SQLException {
        throw Unsupported.feature("Statement.setEscapeProcessing");
    }

    @Override
    public void setFetchDirection(int p0) throws java.sql.SQLException {
        throw Unsupported.feature("Statement.setFetchDirection");
    }

    @Override
    public void setFetchSize(int p0) throws java.sql.SQLException {
        throw Unsupported.feature("Statement.setFetchSize");
    }

    @Override
    public void setMaxFieldSize(int p0) throws java.sql.SQLException {
        throw Unsupported.feature("Statement.setMaxFieldSize");
    }

    @Override
    public void setMaxRows(int p0) throws java.sql.SQLException {
        throw Unsupported.feature("Statement.setMaxRows");
    }

    @Override
    public void setPoolable(boolean p0) throws java.sql.SQLException {
        throw Unsupported.feature("Statement.setPoolable");
    }

    @Override
    public void setQueryTimeout(int p0) throws java.sql.SQLException {
        throw Unsupported.feature("Statement.setQueryTimeout");
    }

    @Override
    public <T> T unwrap(java.lang.Class<T> p0) throws java.sql.SQLException {
        throw Unsupported.feature("Statement.unwrap");
    }
}
