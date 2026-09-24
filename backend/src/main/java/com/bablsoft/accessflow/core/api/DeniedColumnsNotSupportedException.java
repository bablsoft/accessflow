package com.bablsoft.accessflow.core.api;

/**
 * A grant carries {@code denied_columns} on a datasource whose engine does not parse column
 * references (#935): column-level authorization is relational (JSqlParser) only.
 */
public final class DeniedColumnsNotSupportedException extends DatasourceAdminException {

    private final DbType dbType;

    public DeniedColumnsNotSupportedException(DbType dbType) {
        super("denied_columns is not supported for db_type " + dbType);
        this.dbType = dbType;
    }

    public DbType dbType() {
        return dbType;
    }
}
