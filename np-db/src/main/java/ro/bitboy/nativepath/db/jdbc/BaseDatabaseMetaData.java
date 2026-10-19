package ro.bitboy.nativepath.db.jdbc;

/**
 * Generated from {@code java.sql.DatabaseMetaData}: every method throws
 * {@link java.sql.SQLFeatureNotSupportedException}. Implementations override what they support,
 * so anything else fails loudly instead of silently doing nothing.
 */
public abstract class BaseDatabaseMetaData implements java.sql.DatabaseMetaData {

    @Override
    public boolean allProceduresAreCallable() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.allProceduresAreCallable");
    }

    @Override
    public boolean allTablesAreSelectable() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.allTablesAreSelectable");
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.autoCommitFailureClosesAllResultSets");
    }

    @Override
    public boolean dataDefinitionCausesTransactionCommit() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.dataDefinitionCausesTransactionCommit");
    }

    @Override
    public boolean dataDefinitionIgnoredInTransactions() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.dataDefinitionIgnoredInTransactions");
    }

    @Override
    public boolean deletesAreDetected(int p0) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.deletesAreDetected");
    }

    @Override
    public boolean doesMaxRowSizeIncludeBlobs() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.doesMaxRowSizeIncludeBlobs");
    }

    @Override
    public boolean generatedKeyAlwaysReturned() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.generatedKeyAlwaysReturned");
    }

    @Override
    public java.sql.ResultSet getAttributes(java.lang.String p0, java.lang.String p1, java.lang.String p2, java.lang.String p3) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getAttributes");
    }

    @Override
    public java.sql.ResultSet getBestRowIdentifier(java.lang.String p0, java.lang.String p1, java.lang.String p2, int p3, boolean p4) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getBestRowIdentifier");
    }

    @Override
    public java.lang.String getCatalogSeparator() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getCatalogSeparator");
    }

    @Override
    public java.lang.String getCatalogTerm() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getCatalogTerm");
    }

    @Override
    public java.sql.ResultSet getCatalogs() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getCatalogs");
    }

    @Override
    public java.sql.ResultSet getClientInfoProperties() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getClientInfoProperties");
    }

    @Override
    public java.sql.ResultSet getColumnPrivileges(java.lang.String p0, java.lang.String p1, java.lang.String p2, java.lang.String p3) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getColumnPrivileges");
    }

    @Override
    public java.sql.ResultSet getColumns(java.lang.String p0, java.lang.String p1, java.lang.String p2, java.lang.String p3) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getColumns");
    }

    @Override
    public java.sql.Connection getConnection() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getConnection");
    }

    @Override
    public java.sql.ResultSet getCrossReference(java.lang.String p0, java.lang.String p1, java.lang.String p2, java.lang.String p3, java.lang.String p4, java.lang.String p5) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getCrossReference");
    }

    @Override
    public int getDatabaseMajorVersion() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getDatabaseMajorVersion");
    }

    @Override
    public int getDatabaseMinorVersion() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getDatabaseMinorVersion");
    }

    @Override
    public java.lang.String getDatabaseProductName() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getDatabaseProductName");
    }

    @Override
    public java.lang.String getDatabaseProductVersion() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getDatabaseProductVersion");
    }

    @Override
    public int getDefaultTransactionIsolation() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getDefaultTransactionIsolation");
    }

    @Override
    public int getDriverMajorVersion() {
        throw new UnsupportedOperationException("DatabaseMetaData.getDriverMajorVersion");
    }

    @Override
    public int getDriverMinorVersion() {
        throw new UnsupportedOperationException("DatabaseMetaData.getDriverMinorVersion");
    }

    @Override
    public java.lang.String getDriverName() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getDriverName");
    }

    @Override
    public java.lang.String getDriverVersion() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getDriverVersion");
    }

    @Override
    public java.sql.ResultSet getExportedKeys(java.lang.String p0, java.lang.String p1, java.lang.String p2) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getExportedKeys");
    }

    @Override
    public java.lang.String getExtraNameCharacters() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getExtraNameCharacters");
    }

    @Override
    public java.sql.ResultSet getFunctionColumns(java.lang.String p0, java.lang.String p1, java.lang.String p2, java.lang.String p3) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getFunctionColumns");
    }

    @Override
    public java.sql.ResultSet getFunctions(java.lang.String p0, java.lang.String p1, java.lang.String p2) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getFunctions");
    }

    @Override
    public java.lang.String getIdentifierQuoteString() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getIdentifierQuoteString");
    }

    @Override
    public java.sql.ResultSet getImportedKeys(java.lang.String p0, java.lang.String p1, java.lang.String p2) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getImportedKeys");
    }

    @Override
    public java.sql.ResultSet getIndexInfo(java.lang.String p0, java.lang.String p1, java.lang.String p2, boolean p3, boolean p4) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getIndexInfo");
    }

    @Override
    public int getJDBCMajorVersion() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getJDBCMajorVersion");
    }

    @Override
    public int getJDBCMinorVersion() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getJDBCMinorVersion");
    }

    @Override
    public int getMaxBinaryLiteralLength() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getMaxBinaryLiteralLength");
    }

    @Override
    public int getMaxCatalogNameLength() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getMaxCatalogNameLength");
    }

    @Override
    public int getMaxCharLiteralLength() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getMaxCharLiteralLength");
    }

    @Override
    public int getMaxColumnNameLength() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getMaxColumnNameLength");
    }

    @Override
    public int getMaxColumnsInGroupBy() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getMaxColumnsInGroupBy");
    }

    @Override
    public int getMaxColumnsInIndex() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getMaxColumnsInIndex");
    }

    @Override
    public int getMaxColumnsInOrderBy() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getMaxColumnsInOrderBy");
    }

    @Override
    public int getMaxColumnsInSelect() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getMaxColumnsInSelect");
    }

    @Override
    public int getMaxColumnsInTable() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getMaxColumnsInTable");
    }

    @Override
    public int getMaxConnections() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getMaxConnections");
    }

    @Override
    public int getMaxCursorNameLength() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getMaxCursorNameLength");
    }

    @Override
    public int getMaxIndexLength() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getMaxIndexLength");
    }

    @Override
    public int getMaxProcedureNameLength() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getMaxProcedureNameLength");
    }

    @Override
    public int getMaxRowSize() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getMaxRowSize");
    }

    @Override
    public int getMaxSchemaNameLength() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getMaxSchemaNameLength");
    }

    @Override
    public int getMaxStatementLength() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getMaxStatementLength");
    }

    @Override
    public int getMaxStatements() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getMaxStatements");
    }

    @Override
    public int getMaxTableNameLength() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getMaxTableNameLength");
    }

    @Override
    public int getMaxTablesInSelect() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getMaxTablesInSelect");
    }

    @Override
    public int getMaxUserNameLength() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getMaxUserNameLength");
    }

    @Override
    public java.lang.String getNumericFunctions() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getNumericFunctions");
    }

    @Override
    public java.sql.ResultSet getPrimaryKeys(java.lang.String p0, java.lang.String p1, java.lang.String p2) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getPrimaryKeys");
    }

    @Override
    public java.sql.ResultSet getProcedureColumns(java.lang.String p0, java.lang.String p1, java.lang.String p2, java.lang.String p3) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getProcedureColumns");
    }

    @Override
    public java.lang.String getProcedureTerm() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getProcedureTerm");
    }

    @Override
    public java.sql.ResultSet getProcedures(java.lang.String p0, java.lang.String p1, java.lang.String p2) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getProcedures");
    }

    @Override
    public java.sql.ResultSet getPseudoColumns(java.lang.String p0, java.lang.String p1, java.lang.String p2, java.lang.String p3) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getPseudoColumns");
    }

    @Override
    public int getResultSetHoldability() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getResultSetHoldability");
    }

    @Override
    public java.sql.RowIdLifetime getRowIdLifetime() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getRowIdLifetime");
    }

    @Override
    public java.lang.String getSQLKeywords() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getSQLKeywords");
    }

    @Override
    public int getSQLStateType() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getSQLStateType");
    }

    @Override
    public java.lang.String getSchemaTerm() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getSchemaTerm");
    }

    @Override
    public java.sql.ResultSet getSchemas() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getSchemas");
    }

    @Override
    public java.sql.ResultSet getSchemas(java.lang.String p0, java.lang.String p1) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getSchemas");
    }

    @Override
    public java.lang.String getSearchStringEscape() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getSearchStringEscape");
    }

    @Override
    public java.lang.String getStringFunctions() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getStringFunctions");
    }

    @Override
    public java.sql.ResultSet getSuperTables(java.lang.String p0, java.lang.String p1, java.lang.String p2) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getSuperTables");
    }

    @Override
    public java.sql.ResultSet getSuperTypes(java.lang.String p0, java.lang.String p1, java.lang.String p2) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getSuperTypes");
    }

    @Override
    public java.lang.String getSystemFunctions() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getSystemFunctions");
    }

    @Override
    public java.sql.ResultSet getTablePrivileges(java.lang.String p0, java.lang.String p1, java.lang.String p2) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getTablePrivileges");
    }

    @Override
    public java.sql.ResultSet getTableTypes() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getTableTypes");
    }

    @Override
    public java.sql.ResultSet getTables(java.lang.String p0, java.lang.String p1, java.lang.String p2, java.lang.String[] p3) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getTables");
    }

    @Override
    public java.lang.String getTimeDateFunctions() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getTimeDateFunctions");
    }

    @Override
    public java.sql.ResultSet getTypeInfo() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getTypeInfo");
    }

    @Override
    public java.sql.ResultSet getUDTs(java.lang.String p0, java.lang.String p1, java.lang.String p2, int[] p3) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getUDTs");
    }

    @Override
    public java.lang.String getURL() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getURL");
    }

    @Override
    public java.lang.String getUserName() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getUserName");
    }

    @Override
    public java.sql.ResultSet getVersionColumns(java.lang.String p0, java.lang.String p1, java.lang.String p2) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.getVersionColumns");
    }

    @Override
    public boolean insertsAreDetected(int p0) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.insertsAreDetected");
    }

    @Override
    public boolean isCatalogAtStart() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.isCatalogAtStart");
    }

    @Override
    public boolean isReadOnly() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.isReadOnly");
    }

    @Override
    public boolean isWrapperFor(java.lang.Class<?> p0) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.isWrapperFor");
    }

    @Override
    public boolean locatorsUpdateCopy() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.locatorsUpdateCopy");
    }

    @Override
    public boolean nullPlusNonNullIsNull() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.nullPlusNonNullIsNull");
    }

    @Override
    public boolean nullsAreSortedAtEnd() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.nullsAreSortedAtEnd");
    }

    @Override
    public boolean nullsAreSortedAtStart() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.nullsAreSortedAtStart");
    }

    @Override
    public boolean nullsAreSortedHigh() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.nullsAreSortedHigh");
    }

    @Override
    public boolean nullsAreSortedLow() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.nullsAreSortedLow");
    }

    @Override
    public boolean othersDeletesAreVisible(int p0) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.othersDeletesAreVisible");
    }

    @Override
    public boolean othersInsertsAreVisible(int p0) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.othersInsertsAreVisible");
    }

    @Override
    public boolean othersUpdatesAreVisible(int p0) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.othersUpdatesAreVisible");
    }

    @Override
    public boolean ownDeletesAreVisible(int p0) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.ownDeletesAreVisible");
    }

    @Override
    public boolean ownInsertsAreVisible(int p0) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.ownInsertsAreVisible");
    }

    @Override
    public boolean ownUpdatesAreVisible(int p0) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.ownUpdatesAreVisible");
    }

    @Override
    public boolean storesLowerCaseIdentifiers() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.storesLowerCaseIdentifiers");
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.storesLowerCaseQuotedIdentifiers");
    }

    @Override
    public boolean storesMixedCaseIdentifiers() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.storesMixedCaseIdentifiers");
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.storesMixedCaseQuotedIdentifiers");
    }

    @Override
    public boolean storesUpperCaseIdentifiers() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.storesUpperCaseIdentifiers");
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.storesUpperCaseQuotedIdentifiers");
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsANSI92EntryLevelSQL");
    }

    @Override
    public boolean supportsANSI92FullSQL() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsANSI92FullSQL");
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsANSI92IntermediateSQL");
    }

    @Override
    public boolean supportsAlterTableWithAddColumn() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsAlterTableWithAddColumn");
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsAlterTableWithDropColumn");
    }

    @Override
    public boolean supportsBatchUpdates() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsBatchUpdates");
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsCatalogsInDataManipulation");
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsCatalogsInIndexDefinitions");
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsCatalogsInPrivilegeDefinitions");
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsCatalogsInProcedureCalls");
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsCatalogsInTableDefinitions");
    }

    @Override
    public boolean supportsColumnAliasing() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsColumnAliasing");
    }

    @Override
    public boolean supportsConvert() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsConvert");
    }

    @Override
    public boolean supportsConvert(int p0, int p1) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsConvert");
    }

    @Override
    public boolean supportsCoreSQLGrammar() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsCoreSQLGrammar");
    }

    @Override
    public boolean supportsCorrelatedSubqueries() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsCorrelatedSubqueries");
    }

    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsDataDefinitionAndDataManipulationTransactions");
    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsDataManipulationTransactionsOnly");
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsDifferentTableCorrelationNames");
    }

    @Override
    public boolean supportsExpressionsInOrderBy() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsExpressionsInOrderBy");
    }

    @Override
    public boolean supportsExtendedSQLGrammar() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsExtendedSQLGrammar");
    }

    @Override
    public boolean supportsFullOuterJoins() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsFullOuterJoins");
    }

    @Override
    public boolean supportsGetGeneratedKeys() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsGetGeneratedKeys");
    }

    @Override
    public boolean supportsGroupBy() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsGroupBy");
    }

    @Override
    public boolean supportsGroupByBeyondSelect() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsGroupByBeyondSelect");
    }

    @Override
    public boolean supportsGroupByUnrelated() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsGroupByUnrelated");
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsIntegrityEnhancementFacility");
    }

    @Override
    public boolean supportsLikeEscapeClause() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsLikeEscapeClause");
    }

    @Override
    public boolean supportsLimitedOuterJoins() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsLimitedOuterJoins");
    }

    @Override
    public boolean supportsMinimumSQLGrammar() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsMinimumSQLGrammar");
    }

    @Override
    public boolean supportsMixedCaseIdentifiers() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsMixedCaseIdentifiers");
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsMixedCaseQuotedIdentifiers");
    }

    @Override
    public boolean supportsMultipleOpenResults() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsMultipleOpenResults");
    }

    @Override
    public boolean supportsMultipleResultSets() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsMultipleResultSets");
    }

    @Override
    public boolean supportsMultipleTransactions() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsMultipleTransactions");
    }

    @Override
    public boolean supportsNamedParameters() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsNamedParameters");
    }

    @Override
    public boolean supportsNonNullableColumns() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsNonNullableColumns");
    }

    @Override
    public boolean supportsOpenCursorsAcrossCommit() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsOpenCursorsAcrossCommit");
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsOpenCursorsAcrossRollback");
    }

    @Override
    public boolean supportsOpenStatementsAcrossCommit() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsOpenStatementsAcrossCommit");
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsOpenStatementsAcrossRollback");
    }

    @Override
    public boolean supportsOrderByUnrelated() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsOrderByUnrelated");
    }

    @Override
    public boolean supportsOuterJoins() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsOuterJoins");
    }

    @Override
    public boolean supportsPositionedDelete() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsPositionedDelete");
    }

    @Override
    public boolean supportsPositionedUpdate() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsPositionedUpdate");
    }

    @Override
    public boolean supportsResultSetConcurrency(int p0, int p1) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsResultSetConcurrency");
    }

    @Override
    public boolean supportsResultSetHoldability(int p0) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsResultSetHoldability");
    }

    @Override
    public boolean supportsResultSetType(int p0) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsResultSetType");
    }

    @Override
    public boolean supportsSavepoints() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsSavepoints");
    }

    @Override
    public boolean supportsSchemasInDataManipulation() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsSchemasInDataManipulation");
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsSchemasInIndexDefinitions");
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsSchemasInPrivilegeDefinitions");
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsSchemasInProcedureCalls");
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsSchemasInTableDefinitions");
    }

    @Override
    public boolean supportsSelectForUpdate() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsSelectForUpdate");
    }

    @Override
    public boolean supportsStatementPooling() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsStatementPooling");
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsStoredFunctionsUsingCallSyntax");
    }

    @Override
    public boolean supportsStoredProcedures() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsStoredProcedures");
    }

    @Override
    public boolean supportsSubqueriesInComparisons() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsSubqueriesInComparisons");
    }

    @Override
    public boolean supportsSubqueriesInExists() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsSubqueriesInExists");
    }

    @Override
    public boolean supportsSubqueriesInIns() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsSubqueriesInIns");
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsSubqueriesInQuantifieds");
    }

    @Override
    public boolean supportsTableCorrelationNames() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsTableCorrelationNames");
    }

    @Override
    public boolean supportsTransactionIsolationLevel(int p0) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsTransactionIsolationLevel");
    }

    @Override
    public boolean supportsTransactions() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsTransactions");
    }

    @Override
    public boolean supportsUnion() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsUnion");
    }

    @Override
    public boolean supportsUnionAll() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.supportsUnionAll");
    }

    @Override
    public <T> T unwrap(java.lang.Class<T> p0) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.unwrap");
    }

    @Override
    public boolean updatesAreDetected(int p0) throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.updatesAreDetected");
    }

    @Override
    public boolean usesLocalFilePerTable() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.usesLocalFilePerTable");
    }

    @Override
    public boolean usesLocalFiles() throws java.sql.SQLException {
        throw Unsupported.feature("DatabaseMetaData.usesLocalFiles");
    }
}
