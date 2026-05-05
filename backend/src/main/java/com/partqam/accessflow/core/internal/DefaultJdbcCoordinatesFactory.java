package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.JdbcCoordinates;
import com.partqam.accessflow.core.api.JdbcCoordinatesFactory;
import com.partqam.accessflow.core.api.SslMode;
import org.springframework.stereotype.Component;

@Component
class DefaultJdbcCoordinatesFactory implements JdbcCoordinatesFactory {

    @Override
    public JdbcCoordinates from(DbType dbType, String host, int port, String databaseName,
                                String username, SslMode sslMode) {
        return new JdbcCoordinates(
                buildUrl(dbType, host, port, databaseName, sslMode),
                driverClassName(dbType),
                username);
    }

    private static String buildUrl(DbType dbType, String host, int port, String databaseName,
                                   SslMode sslMode) {
        var base = switch (dbType) {
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + databaseName;
            case MYSQL -> "jdbc:mysql://" + host + ":" + port + "/" + databaseName;
        };
        var sslParam = sslModeParam(dbType, sslMode);
        return sslParam.isEmpty() ? base : base + "?" + sslParam;
    }

    private static String sslModeParam(DbType dbType, SslMode sslMode) {
        return switch (dbType) {
            case POSTGRESQL -> switch (sslMode) {
                case DISABLE -> "sslmode=disable";
                case REQUIRE -> "sslmode=require";
                case VERIFY_CA -> "sslmode=verify-ca";
                case VERIFY_FULL -> "sslmode=verify-full";
            };
            case MYSQL -> switch (sslMode) {
                case DISABLE -> "useSSL=false";
                case REQUIRE -> "useSSL=true&requireSSL=true";
                case VERIFY_CA -> "useSSL=true&verifyServerCertificate=true";
                case VERIFY_FULL -> "useSSL=true&verifyServerCertificate=true";
            };
        };
    }

    private static String driverClassName(DbType dbType) {
        return switch (dbType) {
            case POSTGRESQL -> "org.postgresql.Driver";
            case MYSQL -> "com.mysql.cj.jdbc.Driver";
        };
    }
}
