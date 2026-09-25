package com.bablsoft.accessflow.core.api;

/**
 * A grant carries {@code denied_shapes} on a datasource whose engine does not produce a SQL AST
 * (#940): query-shape detection is relational (JSqlParser) only.
 */
public final class DeniedShapesNotSupportedException extends DatasourceAdminException {

    private final DbType dbType;

    public DeniedShapesNotSupportedException(DbType dbType) {
        super("denied_shapes is not supported for db_type " + dbType);
        this.dbType = dbType;
    }

    public DbType dbType() {
        return dbType;
    }
}
