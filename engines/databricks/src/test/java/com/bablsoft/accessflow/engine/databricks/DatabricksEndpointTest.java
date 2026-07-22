package com.bablsoft.accessflow.engine.databricks;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabricksEndpointTest {

    @Test
    void resolvesBareWarehousePathAgainstTheWorkspaceHost() {
        var endpoint = DatabricksEndpoint.resolve(
                descriptor("adb-123.azuredatabricks.net", "/sql/1.0/warehouses/abc123def456"),
                TestMessages.keyEcho());
        assertThat(endpoint.baseUrl()).isEqualTo("https://adb-123.azuredatabricks.net");
        assertThat(endpoint.warehouseId()).isEqualTo("abc123def456");
    }

    @Test
    void stripsWhitespaceAndTrailingSlashSegmentsFromTheBarePath() {
        var endpoint = DatabricksEndpoint.resolve(
                descriptor(" dbc-1.cloud.databricks.com ", " /sql/1.0/warehouses/wh1 "),
                TestMessages.keyEcho());
        assertThat(endpoint.baseUrl()).isEqualTo("https://dbc-1.cloud.databricks.com");
        assertThat(endpoint.warehouseId()).isEqualTo("wh1");
    }

    @Test
    void resolvesFullUrlUsingItsSchemeAndAuthorityAsBase() {
        var endpoint = DatabricksEndpoint.resolve(
                descriptor("ignored.example.com",
                        "http://127.0.0.1:8443/sql/1.0/warehouses/stub"),
                TestMessages.keyEcho());
        assertThat(endpoint.baseUrl()).isEqualTo("http://127.0.0.1:8443");
        assertThat(endpoint.warehouseId()).isEqualTo("stub");
    }

    @Test
    void resolvesHttpsWorkspaceUrl() {
        var endpoint = DatabricksEndpoint.resolve(
                descriptor(null, "https://dbc-2.cloud.databricks.com/sql/1.0/warehouses/w9"),
                TestMessages.keyEcho());
        assertThat(endpoint.baseUrl()).isEqualTo("https://dbc-2.cloud.databricks.com");
        assertThat(endpoint.warehouseId()).isEqualTo("w9");
    }

    @Test
    void rejectsMissingOrBlankOverride() {
        assertInvalid(descriptor("h.example.com", null));
        assertInvalid(descriptor("h.example.com", "  "));
    }

    @Test
    void rejectsPathWithoutWarehousesSegment() {
        assertInvalid(descriptor("h.example.com", "/sql/1.0/endpoints/abc"));
        assertInvalid(descriptor("h.example.com", "/sql/1.0/warehouses/"));
        assertInvalid(descriptor("h.example.com", "/warehouses"));
        assertInvalid(descriptor("h.example.com",
                "https://h.example.com/sql/1.0/warehouses/abc/extra"));
    }

    @Test
    void rejectsBarePathWhenTheWorkspaceHostIsBlank() {
        assertInvalid(descriptor(null, "/sql/1.0/warehouses/abc"));
        assertInvalid(descriptor(" ", "/sql/1.0/warehouses/abc"));
    }

    @Test
    void rejectsRelativeOrGarbageOverride() {
        assertInvalid(descriptor("h.example.com", "sql/1.0/warehouses/abc"));
        assertInvalid(descriptor("h.example.com", "jdbc:databricks://h:443"));
        assertInvalid(descriptor("h.example.com", "https:///sql/1.0/warehouses/abc"));
    }

    private static void assertInvalid(DatasourceConnectionDescriptor descriptor) {
        assertThatThrownBy(() -> DatabricksEndpoint.resolve(descriptor, TestMessages.keyEcho()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("error.databricks.warehouse_path_invalid");
    }

    /**
     * The installed backend snapshot predates the DATABRICKS DbType constant; the engine never
     * reads {@code dbType}, so CUSTOM keeps the tests decoupled from the host enum.
     */
    private static DatasourceConnectionDescriptor descriptor(String host, String override) {
        return new DatasourceConnectionDescriptor(UUID.randomUUID(), UUID.randomUUID(),
                DbType.DATABRICKS, host, null, "main", "token", "pat", SslMode.REQUIRE, 10,
                1000, true, null, false, null, "databricks", override, null, null, null, true,
                null);
    }
}
