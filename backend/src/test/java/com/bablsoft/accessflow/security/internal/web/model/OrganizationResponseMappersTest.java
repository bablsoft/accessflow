package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.OrganizationUsageView;
import com.bablsoft.accessflow.core.api.OrganizationView;
import com.bablsoft.accessflow.core.api.PageResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrganizationResponseMappersTest {

    @Test
    void organizationResponseFromView() {
        var id = UUID.randomUUID();
        var now = Instant.now();
        var view = new OrganizationView(id, "Acme", "acme", true, 10, 50, 1000, now, now);

        var response = OrganizationResponse.from(view);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.name()).isEqualTo("Acme");
        assertThat(response.slug()).isEqualTo("acme");
        assertThat(response.disabled()).isTrue();
        assertThat(response.maxDatasources()).isEqualTo(10);
        assertThat(response.maxUsers()).isEqualTo(50);
        assertThat(response.maxQueriesPerDay()).isEqualTo(1000);
        assertThat(response.createdAt()).isEqualTo(now);
        assertThat(response.updatedAt()).isEqualTo(now);
    }

    @Test
    void usageResponseFromView() {
        var id = UUID.randomUUID();
        var view = new OrganizationUsageView(id, 3, 10, 7, null, 42, 1000);

        var response = OrganizationUsageResponse.from(view);

        assertThat(response.organizationId()).isEqualTo(id);
        assertThat(response.datasourceCount()).isEqualTo(3L);
        assertThat(response.maxDatasources()).isEqualTo(10);
        assertThat(response.userCount()).isEqualTo(7L);
        assertThat(response.maxUsers()).isNull();
        assertThat(response.queriesLast24h()).isEqualTo(42L);
        assertThat(response.maxQueriesPerDay()).isEqualTo(1000);
    }

    @Test
    void pageResponseFromPage() {
        var id = UUID.randomUUID();
        var now = Instant.now();
        var content = List.of(OrganizationResponse.from(
                new OrganizationView(id, "Acme", "acme", false, null, null, null, now, now)));
        var page = new PageResponse<>(content, 0, 20, 1L, 1);

        var response = OrganizationPageResponse.from(page);

        assertThat(response.content()).hasSize(1);
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isEqualTo(1L);
        assertThat(response.totalPages()).isEqualTo(1);
    }
}
