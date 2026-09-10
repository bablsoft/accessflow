package com.bablsoft.accessflow.workflow.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuerySuggestionEntityTest {

    @Test
    void holdsFieldValues() {
        var entity = new QuerySuggestionEntity();
        var id = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var submitter = UUID.randomUUID();
        var first = Instant.parse("2026-06-01T08:00:00Z");
        var last = Instant.parse("2026-09-01T08:00:00Z");
        var computed = Instant.parse("2026-09-10T12:00:00Z");
        entity.setId(id);
        entity.setOrganizationId(orgId);
        entity.setDatasourceId(datasourceId);
        entity.setCanonicalHash("a".repeat(64));
        entity.setSqlText("select id from orders");
        entity.setQueryType(QueryType.SELECT);
        entity.setReferencedTables(new String[]{"public.orders"});
        entity.setSubmitterIds(new UUID[]{submitter});
        entity.setApprovedCount(12);
        entity.setDistinctSubmitterCount(3);
        entity.setFirstSubmittedAt(first);
        entity.setLastSubmittedAt(last);
        entity.setComputedAt(computed);
        entity.setVersion(4L);
        entity.setCreatedAt(first);
        entity.setUpdatedAt(computed);

        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getOrganizationId()).isEqualTo(orgId);
        assertThat(entity.getDatasourceId()).isEqualTo(datasourceId);
        assertThat(entity.getCanonicalHash()).hasSize(64);
        assertThat(entity.getSqlText()).isEqualTo("select id from orders");
        assertThat(entity.getQueryType()).isEqualTo(QueryType.SELECT);
        assertThat(entity.getReferencedTables()).containsExactly("public.orders");
        assertThat(entity.getSubmitterIds()).containsExactly(submitter);
        assertThat(entity.getApprovedCount()).isEqualTo(12);
        assertThat(entity.getDistinctSubmitterCount()).isEqualTo(3);
        assertThat(entity.getFirstSubmittedAt()).isEqualTo(first);
        assertThat(entity.getLastSubmittedAt()).isEqualTo(last);
        assertThat(entity.getComputedAt()).isEqualTo(computed);
        assertThat(entity.getVersion()).isEqualTo(4L);
        assertThat(entity.getCreatedAt()).isEqualTo(first);
        assertThat(entity.getUpdatedAt()).isEqualTo(computed);
    }

    @Test
    void arrayFieldsDefaultToEmptyRatherThanNull() {
        var entity = new QuerySuggestionEntity();

        assertThat(entity.getReferencedTables()).isEmpty();
        assertThat(entity.getSubmitterIds()).isEmpty();
    }
}
