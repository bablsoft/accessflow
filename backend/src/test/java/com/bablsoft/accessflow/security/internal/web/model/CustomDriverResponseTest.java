package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.CustomDriverView;
import com.bablsoft.accessflow.core.api.DbType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CustomDriverResponseTest {

    @Test
    void fromCopiesEveryFieldFromView() {
        var id = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var uploaderId = UUID.randomUUID();
        var createdAt = Instant.now();
        var view = new CustomDriverView(id, orgId, "Vendor", DbType.ORACLE,
                "oracle.jdbc.OracleDriver", "ojdbc.jar",
                "a".repeat(64), 12345L, uploaderId, "Alice", createdAt);

        var response = CustomDriverResponse.from(view);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.organizationId()).isEqualTo(orgId);
        assertThat(response.vendorName()).isEqualTo("Vendor");
        assertThat(response.targetDbType()).isEqualTo(DbType.ORACLE);
        assertThat(response.driverClass()).isEqualTo("oracle.jdbc.OracleDriver");
        assertThat(response.jarFilename()).isEqualTo("ojdbc.jar");
        assertThat(response.jarSha256()).isEqualTo("a".repeat(64));
        assertThat(response.jarSizeBytes()).isEqualTo(12345L);
        assertThat(response.uploadedByUserId()).isEqualTo(uploaderId);
        assertThat(response.uploadedByDisplayName()).isEqualTo("Alice");
        assertThat(response.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void listResponseWrapsEachViewIntoAResponse() {
        var view1 = new CustomDriverView(UUID.randomUUID(), UUID.randomUUID(), "V1",
                DbType.MYSQL, "com.A", "a.jar", "a".repeat(64), 1, UUID.randomUUID(), "u",
                Instant.now());
        var view2 = new CustomDriverView(UUID.randomUUID(), UUID.randomUUID(), "V2",
                DbType.CUSTOM, "com.B", "b.jar", "b".repeat(64), 2, UUID.randomUUID(), "u",
                Instant.now());

        var response = CustomDriverListResponse.from(List.of(view1, view2));

        assertThat(response.drivers()).hasSize(2);
        assertThat(response.drivers().get(0).vendorName()).isEqualTo("V1");
        assertThat(response.drivers().get(1).targetDbType()).isEqualTo(DbType.CUSTOM);
    }
}
