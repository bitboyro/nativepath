package ro.bitboy.nativepath.db.jdbc;

/**
 * Generated from {@code java.sql.PreparedStatement}: every method throws
 * {@link java.sql.SQLFeatureNotSupportedException}. Implementations override what they support,
 * so anything else fails loudly instead of silently doing nothing.
 */
public interface UnsupportedPreparedStatement extends java.sql.PreparedStatement {

    @Override
    default void addBatch() throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.addBatch");
    }

    @Override
    default void clearParameters() throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.clearParameters");
    }

    @Override
    default boolean execute() throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.execute");
    }

    @Override
    default java.sql.ResultSet executeQuery() throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.executeQuery");
    }

    @Override
    default int executeUpdate() throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.executeUpdate");
    }

    @Override
    default java.sql.ResultSetMetaData getMetaData() throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.getMetaData");
    }

    @Override
    default java.sql.ParameterMetaData getParameterMetaData() throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.getParameterMetaData");
    }

    @Override
    default void setArray(int p0, java.sql.Array p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setArray");
    }

    @Override
    default void setAsciiStream(int p0, java.io.InputStream p1, int p2) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setAsciiStream");
    }

    @Override
    default void setAsciiStream(int p0, java.io.InputStream p1, long p2) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setAsciiStream");
    }

    @Override
    default void setAsciiStream(int p0, java.io.InputStream p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setAsciiStream");
    }

    @Override
    default void setBigDecimal(int p0, java.math.BigDecimal p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setBigDecimal");
    }

    @Override
    default void setBinaryStream(int p0, java.io.InputStream p1, int p2) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setBinaryStream");
    }

    @Override
    default void setBinaryStream(int p0, java.io.InputStream p1, long p2) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setBinaryStream");
    }

    @Override
    default void setBinaryStream(int p0, java.io.InputStream p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setBinaryStream");
    }

    @Override
    default void setBlob(int p0, java.io.InputStream p1, long p2) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setBlob");
    }

    @Override
    default void setBlob(int p0, java.io.InputStream p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setBlob");
    }

    @Override
    default void setBlob(int p0, java.sql.Blob p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setBlob");
    }

    @Override
    default void setBoolean(int p0, boolean p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setBoolean");
    }

    @Override
    default void setByte(int p0, byte p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setByte");
    }

    @Override
    default void setBytes(int p0, byte[] p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setBytes");
    }

    @Override
    default void setCharacterStream(int p0, java.io.Reader p1, int p2) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setCharacterStream");
    }

    @Override
    default void setCharacterStream(int p0, java.io.Reader p1, long p2) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setCharacterStream");
    }

    @Override
    default void setCharacterStream(int p0, java.io.Reader p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setCharacterStream");
    }

    @Override
    default void setClob(int p0, java.io.Reader p1, long p2) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setClob");
    }

    @Override
    default void setClob(int p0, java.io.Reader p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setClob");
    }

    @Override
    default void setClob(int p0, java.sql.Clob p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setClob");
    }

    @Override
    default void setDate(int p0, java.sql.Date p1, java.util.Calendar p2) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setDate");
    }

    @Override
    default void setDate(int p0, java.sql.Date p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setDate");
    }

    @Override
    default void setDouble(int p0, double p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setDouble");
    }

    @Override
    default void setFloat(int p0, float p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setFloat");
    }

    @Override
    default void setInt(int p0, int p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setInt");
    }

    @Override
    default void setLong(int p0, long p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setLong");
    }

    @Override
    default void setNCharacterStream(int p0, java.io.Reader p1, long p2) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setNCharacterStream");
    }

    @Override
    default void setNCharacterStream(int p0, java.io.Reader p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setNCharacterStream");
    }

    @Override
    default void setNClob(int p0, java.io.Reader p1, long p2) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setNClob");
    }

    @Override
    default void setNClob(int p0, java.io.Reader p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setNClob");
    }

    @Override
    default void setNClob(int p0, java.sql.NClob p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setNClob");
    }

    @Override
    default void setNString(int p0, java.lang.String p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setNString");
    }

    @Override
    default void setNull(int p0, int p1, java.lang.String p2) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setNull");
    }

    @Override
    default void setNull(int p0, int p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setNull");
    }

    @Override
    default void setObject(int p0, java.lang.Object p1, int p2, int p3) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setObject");
    }

    @Override
    default void setObject(int p0, java.lang.Object p1, int p2) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setObject");
    }

    @Override
    default void setObject(int p0, java.lang.Object p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setObject");
    }

    @Override
    default void setRef(int p0, java.sql.Ref p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setRef");
    }

    @Override
    default void setRowId(int p0, java.sql.RowId p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setRowId");
    }

    @Override
    default void setSQLXML(int p0, java.sql.SQLXML p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setSQLXML");
    }

    @Override
    default void setShort(int p0, short p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setShort");
    }

    @Override
    default void setString(int p0, java.lang.String p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setString");
    }

    @Override
    default void setTime(int p0, java.sql.Time p1, java.util.Calendar p2) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setTime");
    }

    @Override
    default void setTime(int p0, java.sql.Time p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setTime");
    }

    @Override
    default void setTimestamp(int p0, java.sql.Timestamp p1, java.util.Calendar p2) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setTimestamp");
    }

    @Override
    default void setTimestamp(int p0, java.sql.Timestamp p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setTimestamp");
    }

    @Override
    default void setURL(int p0, java.net.URL p1) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setURL");
    }

    @Override
    default void setUnicodeStream(int p0, java.io.InputStream p1, int p2) throws java.sql.SQLException {
        throw Unsupported.feature("PreparedStatement.setUnicodeStream");
    }
}
