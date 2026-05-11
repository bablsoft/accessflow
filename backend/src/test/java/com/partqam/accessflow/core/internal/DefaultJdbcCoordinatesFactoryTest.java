package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.SslMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultJdbcCoordinatesFactoryTest {

    private final DefaultJdbcCoordinatesFactory factory = new DefaultJdbcCoordinatesFactory();

    @Test
    void postgresUrlWithDisableSsl() {
        var coords = factory.from(DbType.POSTGRESQL, "h", 5432, "appdb", "svc", SslMode.DISABLE);

        assertThat(coords.url()).isEqualTo("jdbc:postgresql://h:5432/appdb?sslmode=disable");
        assertThat(coords.driverClassName()).isEqualTo("org.postgresql.Driver");
        assertThat(coords.username()).isEqualTo("svc");
    }

    @Test
    void postgresUrlWithRequire() {
        var coords = factory.from(DbType.POSTGRESQL, "h", 5432, "db", "u", SslMode.REQUIRE);
        assertThat(coords.url()).endsWith("?sslmode=require");
    }

    @Test
    void postgresUrlWithVerifyCa() {
        var coords = factory.from(DbType.POSTGRESQL, "h", 5432, "db", "u", SslMode.VERIFY_CA);
        assertThat(coords.url()).endsWith("?sslmode=verify-ca");
    }

    @Test
    void postgresUrlWithVerifyFull() {
        var coords = factory.from(DbType.POSTGRESQL, "h", 5432, "db", "u", SslMode.VERIFY_FULL);
        assertThat(coords.url()).endsWith("?sslmode=verify-full");
    }

    @Test
    void mysqlUrlWithDisableSsl() {
        var coords = factory.from(DbType.MYSQL, "h", 3306, "appdb", "svc", SslMode.DISABLE);

        assertThat(coords.url())
                .isEqualTo("jdbc:mysql://h:3306/appdb?useSSL=false&allowPublicKeyRetrieval=true");
        assertThat(coords.driverClassName()).isEqualTo("com.mysql.cj.jdbc.Driver");
    }

    @Test
    void mysqlUrlWithRequire() {
        var coords = factory.from(DbType.MYSQL, "h", 3306, "db", "u", SslMode.REQUIRE);
        assertThat(coords.url()).endsWith("?useSSL=true&requireSSL=true");
    }

    @Test
    void mysqlUrlWithVerifyCa() {
        var coords = factory.from(DbType.MYSQL, "h", 3306, "db", "u", SslMode.VERIFY_CA);
        assertThat(coords.url()).endsWith("?useSSL=true&verifyServerCertificate=true");
    }

    @Test
    void mysqlUrlWithVerifyFull() {
        var coords = factory.from(DbType.MYSQL, "h", 3306, "db", "u", SslMode.VERIFY_FULL);
        assertThat(coords.url()).endsWith("?useSSL=true&verifyServerCertificate=true");
    }

    @Test
    void mariadbUrlWithDisableSsl() {
        var coords = factory.from(DbType.MARIADB, "h", 3306, "appdb", "svc", SslMode.DISABLE);
        assertThat(coords.url()).isEqualTo("jdbc:mariadb://h:3306/appdb?useSsl=false");
        assertThat(coords.driverClassName()).isEqualTo("org.mariadb.jdbc.Driver");
    }

    @Test
    void mariadbUrlWithRequire() {
        var coords = factory.from(DbType.MARIADB, "h", 3306, "db", "u", SslMode.REQUIRE);
        assertThat(coords.url()).endsWith("?useSsl=true&trustServerCertificate=true");
    }

    @Test
    void mariadbUrlWithVerifyFull() {
        var coords = factory.from(DbType.MARIADB, "h", 3306, "db", "u", SslMode.VERIFY_FULL);
        assertThat(coords.url()).endsWith("?useSsl=true&trustServerCertificate=false");
    }

    @Test
    void oracleUrlOmitsSslSuffix() {
        var coords = factory.from(DbType.ORACLE, "h", 1521, "ORCL", "svc", SslMode.REQUIRE);
        assertThat(coords.url()).isEqualTo("jdbc:oracle:thin:@//h:1521/ORCL");
        assertThat(coords.driverClassName()).isEqualTo("oracle.jdbc.OracleDriver");
    }

    @Test
    void mssqlUrlWithDisableSsl() {
        var coords = factory.from(DbType.MSSQL, "h", 1433, "appdb", "svc", SslMode.DISABLE);
        assertThat(coords.url())
                .isEqualTo("jdbc:sqlserver://h:1433;databaseName=appdb;encrypt=false");
        assertThat(coords.driverClassName())
                .isEqualTo("com.microsoft.sqlserver.jdbc.SQLServerDriver");
    }

    @Test
    void mssqlUrlWithRequireTrustsServerCert() {
        var coords = factory.from(DbType.MSSQL, "h", 1433, "db", "u", SslMode.REQUIRE);
        assertThat(coords.url())
                .endsWith(";encrypt=true;trustServerCertificate=true");
    }

    @Test
    void mssqlUrlWithVerifyFullPinsServerCert() {
        var coords = factory.from(DbType.MSSQL, "h", 1433, "db", "u", SslMode.VERIFY_FULL);
        assertThat(coords.url())
                .endsWith(";encrypt=true;trustServerCertificate=false");
    }
}
