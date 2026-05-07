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
        return switch (dbType) {
            case POSTGRESQL -> appendQuery(
                    "jdbc:postgresql://" + host + ":" + port + "/" + databaseName,
                    postgresSslParam(sslMode));
            case MYSQL -> appendQuery(
                    "jdbc:mysql://" + host + ":" + port + "/" + databaseName,
                    mysqlSslParam(sslMode));
            case MARIADB -> appendQuery(
                    "jdbc:mariadb://" + host + ":" + port + "/" + databaseName,
                    mariadbSslParam(sslMode));
            case ORACLE -> "jdbc:oracle:thin:@//" + host + ":" + port + "/" + databaseName;
            case MSSQL -> "jdbc:sqlserver://" + host + ":" + port
                    + ";databaseName=" + databaseName + mssqlSslSuffix(sslMode);
        };
    }

    private static String appendQuery(String base, String params) {
        return params.isEmpty() ? base : base + "?" + params;
    }

    private static String postgresSslParam(SslMode sslMode) {
        return switch (sslMode) {
            case DISABLE -> "sslmode=disable";
            case REQUIRE -> "sslmode=require";
            case VERIFY_CA -> "sslmode=verify-ca";
            case VERIFY_FULL -> "sslmode=verify-full";
        };
    }

    private static String mysqlSslParam(SslMode sslMode) {
        return switch (sslMode) {
            case DISABLE -> "useSSL=false";
            case REQUIRE -> "useSSL=true&requireSSL=true";
            case VERIFY_CA, VERIFY_FULL -> "useSSL=true&verifyServerCertificate=true";
        };
    }

    private static String mariadbSslParam(SslMode sslMode) {
        return switch (sslMode) {
            case DISABLE -> "useSsl=false";
            case REQUIRE -> "useSsl=true&trustServerCertificate=true";
            case VERIFY_CA, VERIFY_FULL -> "useSsl=true&trustServerCertificate=false";
        };
    }

    private static String mssqlSslSuffix(SslMode sslMode) {
        return switch (sslMode) {
            case DISABLE -> ";encrypt=false";
            case REQUIRE -> ";encrypt=true;trustServerCertificate=true";
            case VERIFY_CA, VERIFY_FULL -> ";encrypt=true;trustServerCertificate=false";
        };
    }

    private static String driverClassName(DbType dbType) {
        return switch (dbType) {
            case POSTGRESQL -> "org.postgresql.Driver";
            case MYSQL -> "com.mysql.cj.jdbc.Driver";
            case MARIADB -> "org.mariadb.jdbc.Driver";
            case ORACLE -> "oracle.jdbc.OracleDriver";
            case MSSQL -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
        };
    }
}
