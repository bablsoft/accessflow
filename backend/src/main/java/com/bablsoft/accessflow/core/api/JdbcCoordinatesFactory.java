package com.bablsoft.accessflow.core.api;

public interface JdbcCoordinatesFactory {

    JdbcCoordinates from(DbType dbType, String host, int port, String databaseName,
                         String username, SslMode sslMode);
}
