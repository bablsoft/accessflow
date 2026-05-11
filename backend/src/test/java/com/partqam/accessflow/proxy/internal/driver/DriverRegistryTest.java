package com.partqam.accessflow.proxy.internal.driver;

import com.partqam.accessflow.core.api.DbType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DriverRegistryTest {

    @Test
    void hasEntryForEveryDbType() {
        for (var dbType : DbType.values()) {
            var entry = DriverRegistry.require(dbType);
            assertThat(entry).isNotNull();
            assertThat(entry.dbType()).isEqualTo(dbType);
            assertThat(entry.displayName()).isNotBlank();
            assertThat(entry.iconUrl()).startsWith("/db-icons/");
            assertThat(entry.defaultPort()).isPositive();
            assertThat(entry.defaultSslMode()).isNotNull();
            assertThat(entry.jdbcUrlTemplate()).contains("{host}").contains("{port}");
            assertThat(entry.driverClassName()).isNotBlank();
        }
    }

    @Test
    void postgresEntryIsBundledAndHasNoChecksum() {
        var entry = DriverRegistry.require(DbType.POSTGRESQL);
        assertThat(entry.bundled()).isTrue();
        assertThat(entry.driverClassName()).isEqualTo("org.postgresql.Driver");
    }

    @Test
    void externalEntriesPinVersionAndSha256() {
        for (var dbType : DbType.values()) {
            if (dbType == DbType.POSTGRESQL) {
                continue;
            }
            var entry = DriverRegistry.require(dbType);
            assertThat(entry.bundled()).isFalse();
            assertThat(entry.version()).matches("\\d+(\\.\\d+)+([.-]?[A-Za-z0-9]+)*")
                    .as(dbType + " version pin");
            assertThat(entry.sha256()).hasSize(64).matches("[0-9a-fA-F]+");
            assertThat(entry.groupId()).isNotBlank();
            assertThat(entry.artifactId()).isNotBlank();
        }
    }

    @Test
    void mavenPathDerivedFromCoordinates() {
        var entry = DriverRegistry.require(DbType.MYSQL);
        assertThat(entry.mavenPath()).matches(
                "com/mysql/mysql-connector-j/\\d+\\.\\d+\\.\\d+/mysql-connector-j-\\d+\\.\\d+\\.\\d+\\.jar");
    }

    @Test
    void everyDbTypeResolvesToItsOwnIcon() {
        assertThat(DriverRegistry.require(DbType.POSTGRESQL).iconUrl()).isEqualTo("/db-icons/postgresql.svg");
        assertThat(DriverRegistry.require(DbType.MYSQL).iconUrl()).isEqualTo("/db-icons/mysql.svg");
        assertThat(DriverRegistry.require(DbType.MARIADB).iconUrl()).isEqualTo("/db-icons/mariadb.svg");
        assertThat(DriverRegistry.require(DbType.ORACLE).iconUrl()).isEqualTo("/db-icons/oracle.svg");
        assertThat(DriverRegistry.require(DbType.MSSQL).iconUrl()).isEqualTo("/db-icons/mssql.svg");
    }
}
