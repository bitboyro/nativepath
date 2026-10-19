package ro.bitboy.nativepath.db.jdbc;

/**
 * Generated from {@code java.sql.Connection}: every method throws
 * {@link java.sql.SQLFeatureNotSupportedException}. Implementations override what they support,
 * so anything else fails loudly instead of silently doing nothing.
 */
public abstract class BaseConnection implements java.sql.Connection {

    @Override
    public void abort(java.util.concurrent.Executor p0) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.abort");
    }

    @Override
    public void clearWarnings() throws java.sql.SQLException {
        throw Unsupported.feature("Connection.clearWarnings");
    }

    @Override
    public void close() throws java.sql.SQLException {
        throw Unsupported.feature("Connection.close");
    }

    @Override
    public void commit() throws java.sql.SQLException {
        throw Unsupported.feature("Connection.commit");
    }

    @Override
    public java.sql.Array createArrayOf(java.lang.String p0, java.lang.Object[] p1) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.createArrayOf");
    }

    @Override
    public java.sql.Blob createBlob() throws java.sql.SQLException {
        throw Unsupported.feature("Connection.createBlob");
    }

    @Override
    public java.sql.Clob createClob() throws java.sql.SQLException {
        throw Unsupported.feature("Connection.createClob");
    }

    @Override
    public java.sql.NClob createNClob() throws java.sql.SQLException {
        throw Unsupported.feature("Connection.createNClob");
    }

    @Override
    public java.sql.SQLXML createSQLXML() throws java.sql.SQLException {
        throw Unsupported.feature("Connection.createSQLXML");
    }

    @Override
    public java.sql.Statement createStatement() throws java.sql.SQLException {
        throw Unsupported.feature("Connection.createStatement");
    }

    @Override
    public java.sql.Statement createStatement(int p0, int p1, int p2) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.createStatement");
    }

    @Override
    public java.sql.Statement createStatement(int p0, int p1) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.createStatement");
    }

    @Override
    public java.sql.Struct createStruct(java.lang.String p0, java.lang.Object[] p1) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.createStruct");
    }

    @Override
    public boolean getAutoCommit() throws java.sql.SQLException {
        throw Unsupported.feature("Connection.getAutoCommit");
    }

    @Override
    public java.lang.String getCatalog() throws java.sql.SQLException {
        throw Unsupported.feature("Connection.getCatalog");
    }

    @Override
    public java.util.Properties getClientInfo() throws java.sql.SQLException {
        throw Unsupported.feature("Connection.getClientInfo");
    }

    @Override
    public java.lang.String getClientInfo(java.lang.String p0) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.getClientInfo");
    }

    @Override
    public int getHoldability() throws java.sql.SQLException {
        throw Unsupported.feature("Connection.getHoldability");
    }

    @Override
    public java.sql.DatabaseMetaData getMetaData() throws java.sql.SQLException {
        throw Unsupported.feature("Connection.getMetaData");
    }

    @Override
    public int getNetworkTimeout() throws java.sql.SQLException {
        throw Unsupported.feature("Connection.getNetworkTimeout");
    }

    @Override
    public java.lang.String getSchema() throws java.sql.SQLException {
        throw Unsupported.feature("Connection.getSchema");
    }

    @Override
    public int getTransactionIsolation() throws java.sql.SQLException {
        throw Unsupported.feature("Connection.getTransactionIsolation");
    }

    @Override
    public java.util.Map<java.lang.String, java.lang.Class<?>> getTypeMap() throws java.sql.SQLException {
        throw Unsupported.feature("Connection.getTypeMap");
    }

    @Override
    public java.sql.SQLWarning getWarnings() throws java.sql.SQLException {
        throw Unsupported.feature("Connection.getWarnings");
    }

    @Override
    public boolean isClosed() throws java.sql.SQLException {
        throw Unsupported.feature("Connection.isClosed");
    }

    @Override
    public boolean isReadOnly() throws java.sql.SQLException {
        throw Unsupported.feature("Connection.isReadOnly");
    }

    @Override
    public boolean isValid(int p0) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.isValid");
    }

    @Override
    public boolean isWrapperFor(java.lang.Class<?> p0) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.isWrapperFor");
    }

    @Override
    public java.lang.String nativeSQL(java.lang.String p0) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.nativeSQL");
    }

    @Override
    public java.sql.CallableStatement prepareCall(java.lang.String p0, int p1, int p2, int p3) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.prepareCall");
    }

    @Override
    public java.sql.CallableStatement prepareCall(java.lang.String p0, int p1, int p2) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.prepareCall");
    }

    @Override
    public java.sql.CallableStatement prepareCall(java.lang.String p0) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.prepareCall");
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String p0, int[] p1) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.prepareStatement");
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String p0, java.lang.String[] p1) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.prepareStatement");
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String p0, int p1, int p2, int p3) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.prepareStatement");
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String p0, int p1, int p2) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.prepareStatement");
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String p0, int p1) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.prepareStatement");
    }

    @Override
    public java.sql.PreparedStatement prepareStatement(java.lang.String p0) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.prepareStatement");
    }

    @Override
    public void releaseSavepoint(java.sql.Savepoint p0) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.releaseSavepoint");
    }

    @Override
    public void rollback() throws java.sql.SQLException {
        throw Unsupported.feature("Connection.rollback");
    }

    @Override
    public void rollback(java.sql.Savepoint p0) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.rollback");
    }

    @Override
    public void setAutoCommit(boolean p0) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.setAutoCommit");
    }

    @Override
    public void setCatalog(java.lang.String p0) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.setCatalog");
    }

    @Override
    public void setClientInfo(java.lang.String p0, java.lang.String p1) throws java.sql.SQLClientInfoException {
        throw new UnsupportedOperationException("Connection.setClientInfo");
    }

    @Override
    public void setClientInfo(java.util.Properties p0) throws java.sql.SQLClientInfoException {
        throw new UnsupportedOperationException("Connection.setClientInfo");
    }

    @Override
    public void setHoldability(int p0) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.setHoldability");
    }

    @Override
    public void setNetworkTimeout(java.util.concurrent.Executor p0, int p1) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.setNetworkTimeout");
    }

    @Override
    public void setReadOnly(boolean p0) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.setReadOnly");
    }

    @Override
    public java.sql.Savepoint setSavepoint() throws java.sql.SQLException {
        throw Unsupported.feature("Connection.setSavepoint");
    }

    @Override
    public java.sql.Savepoint setSavepoint(java.lang.String p0) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.setSavepoint");
    }

    @Override
    public void setSchema(java.lang.String p0) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.setSchema");
    }

    @Override
    public void setTransactionIsolation(int p0) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.setTransactionIsolation");
    }

    @Override
    public void setTypeMap(java.util.Map<java.lang.String, java.lang.Class<?>> p0) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.setTypeMap");
    }

    @Override
    public <T> T unwrap(java.lang.Class<T> p0) throws java.sql.SQLException {
        throw Unsupported.feature("Connection.unwrap");
    }
}
