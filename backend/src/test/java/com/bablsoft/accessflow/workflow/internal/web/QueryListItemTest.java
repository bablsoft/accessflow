package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.QueryListItemView;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueryListItemTest {

    @Test
    void fromBuildsNestedRefsAndCopiesAllFields() {
        var queryId = UUID.randomUUID();
        var dsId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var view = new QueryListItemView(queryId, dsId, "Prod PG",
                userId, "alice@example.com", "Alice",
                QueryType.SELECT, QueryStatus.PENDING_REVIEW,
                RiskLevel.HIGH, 80,
                Instant.parse("2026-05-01T10:00:00Z"));

        var item = QueryListItem.from(view);

        assertThat(item.id()).isEqualTo(queryId);
        assertThat(item.datasource()).isEqualTo(new QueryListItem.DatasourceRef(dsId, "Prod PG"));
        assertThat(item.submittedBy())
                .isEqualTo(new QueryListItem.SubmitterRef(userId, "alice@example.com", "Alice"));
        assertThat(item.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(item.status()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(item.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(item.riskScore()).isEqualTo(80);
        assertThat(item.createdAt()).isEqualTo(Instant.parse("2026-05-01T10:00:00Z"));
    }

    @Test
    void fromHandlesNullRiskFields() {
        var view = new QueryListItemView(UUID.randomUUID(), UUID.randomUUID(), "ds",
                UUID.randomUUID(), "a@b.com", "A",
                QueryType.SELECT, QueryStatus.PENDING_AI, null, null,
                Instant.now());

        var item = QueryListItem.from(view);

        assertThat(item.riskLevel()).isNull();
        assertThat(item.riskScore()).isNull();
    }
}
