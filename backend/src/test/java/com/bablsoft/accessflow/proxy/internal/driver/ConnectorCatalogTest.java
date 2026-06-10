package com.bablsoft.accessflow.proxy.internal.driver;

import com.bablsoft.accessflow.core.api.DbType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectorCatalogTest {

    private final ConnectorCatalog catalog = new ConnectorCatalog();

    @Test
    void loadsBundledManifestsFromClasspath() {
        assertThat(catalog.all()).extracting(ConnectorManifest::id)
                .contains("postgresql", "mysql", "mariadb", "oracle", "mssql", "clickhouse");
    }

    @Test
    void dialectsPrecedeCustomConnectorsInOrder() {
        var ids = catalog.all().stream().map(ConnectorManifest::id).toList();
        // PostgreSQL is the first dialect (DbType ordinal 0); clickhouse (CUSTOM) sorts last.
        assertThat(ids.get(0)).isEqualTo("postgresql");
        assertThat(ids.getLast()).isEqualTo("clickhouse");
    }

    @Test
    void byDbTypeResolvesEachDialect() {
        assertThat(catalog.requireByDbType(DbType.POSTGRESQL).bundled()).isTrue();
        assertThat(catalog.requireByDbType(DbType.MYSQL).driverClassName())
                .isEqualTo("com.mysql.cj.jdbc.Driver");
        assertThat(catalog.byDbType(DbType.MSSQL)).isPresent();
    }

    @Test
    void byDbTypeForCustomIsEmpty() {
        // CUSTOM connectors (clickhouse) are reachable by id, not by dialect.
        assertThat(catalog.byDbType(DbType.CUSTOM)).isEmpty();
    }

    @Test
    void byIdResolvesCustomConnector() {
        var clickhouse = catalog.byId("clickhouse").orElseThrow();
        assertThat(clickhouse.dbType()).isEqualTo(DbType.CUSTOM);
        assertThat(clickhouse.bundled()).isFalse();
        assertThat(clickhouse.driver().jarFileName()).isEqualTo("clickhouse-jdbc-0.9.0-all.jar");
        assertThat(clickhouse.sha256()).hasSize(64);
    }

    @Test
    void byIdUnknownIsEmpty() {
        assertThat(catalog.byId("does-not-exist")).isEmpty();
    }

    @Test
    void requireByDbTypeThrowsForCustom() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> catalog.requireByDbType(DbType.CUSTOM))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everyManifestPinsAValidShaUnlessBundled() {
        for (var manifest : catalog.all()) {
            if (manifest.bundled()) {
                assertThat(manifest.driver()).isNull();
            } else {
                assertThat(manifest.sha256()).matches("[0-9a-f]{64}");
                if (manifest.requiresEngine()) {
                    // Engine-managed (non-RELATIONAL) connectors pin an engine-plugin jar
                    // (AF-414), not a JDBC driver.
                    assertThat(manifest.jdbcUrlTemplate()).isNull();
                } else {
                    assertThat(manifest.jdbcUrlTemplate()).contains("{host}").contains("{port}");
                }
            }
        }
    }
}
