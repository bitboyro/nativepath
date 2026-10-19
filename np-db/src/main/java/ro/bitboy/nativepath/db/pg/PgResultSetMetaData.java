package ro.bitboy.nativepath.db.pg;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/** Column metadata from the RowDescription. Table and schema names are not resolved yet. */
final class PgResultSetMetaData implements ResultSetMetaData {

    private final PgResults.Result result;

    PgResultSetMetaData(PgResults.Result result) {
        this.result = result;
    }

    private int oid(int column) throws SQLException {
        if (column < 1 || column > result.ncols()) {
            throw new SQLException("column index " + column + " is out of range (1.." + result.ncols() + ")", "22023");
        }
        return result.typeOids()[column - 1];
    }

    @Override public int getColumnCount() { return result.ncols(); }
    @Override public String getColumnLabel(int c) throws SQLException { oid(c); return result.names()[c - 1]; }
    @Override public String getColumnName(int c) throws SQLException { return getColumnLabel(c); }
    @Override public int getColumnType(int c) throws SQLException { return PgTypes.sqlType(oid(c)); }
    @Override public String getColumnTypeName(int c) throws SQLException { return PgTypes.typeName(oid(c)); }
    @Override public String getColumnClassName(int c) throws SQLException { return PgTypes.className(oid(c)); }
    @Override public boolean isSigned(int c) throws SQLException { return PgTypes.isNumeric(oid(c)); }
    @Override public int isNullable(int c) throws SQLException { oid(c); return columnNullableUnknown; }
    @Override public boolean isAutoIncrement(int c) throws SQLException { oid(c); return false; }
    @Override public boolean isCaseSensitive(int c) throws SQLException { return !PgTypes.isNumeric(oid(c)); }
    @Override public boolean isSearchable(int c) throws SQLException { oid(c); return true; }
    @Override public boolean isCurrency(int c) throws SQLException { oid(c); return false; }
    @Override public int getColumnDisplaySize(int c) throws SQLException { oid(c); return Integer.MAX_VALUE; }
    @Override public int getPrecision(int c) throws SQLException { oid(c); return 0; }
    @Override public int getScale(int c) throws SQLException { oid(c); return 0; }
    @Override public String getSchemaName(int c) throws SQLException { oid(c); return ""; }
    @Override public String getTableName(int c) throws SQLException { oid(c); return ""; }
    @Override public String getCatalogName(int c) throws SQLException { oid(c); return ""; }
    @Override public boolean isReadOnly(int c) throws SQLException { oid(c); return false; }
    @Override public boolean isWritable(int c) throws SQLException { oid(c); return true; }
    @Override public boolean isDefinitelyWritable(int c) throws SQLException { oid(c); return false; }

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
