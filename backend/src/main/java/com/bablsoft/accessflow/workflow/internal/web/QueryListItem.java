package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.QueryListItemView;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;

import java.time.Instant;
import java.util.UUID;

/** Row in the {@code GET /queries} response. */
public record QueryListItem(
        UUID id,
        DatasourceRef datasource,
        SubmitterRef submittedBy,
        QueryType queryType,
        QueryStatus status,
        RiskLevel riskLevel,
        Integer riskScore,
        Instant createdAt) {

    public static QueryListItem from(QueryListItemView view) {
        return new QueryListItem(
                view.id(),
                new DatasourceRef(view.datasourceId(), view.datasourceName()),
                new SubmitterRef(view.submittedByUserId(), view.submittedByEmail(),
                        view.submittedByDisplayName()),
                view.queryType(),
                view.status(),
                view.aiRiskLevel(),
                view.aiRiskScore(),
                view.createdAt());
    }

    public record DatasourceRef(UUID id, String name) {
    }

    public record SubmitterRef(UUID id, String email, String displayName) {
    }
}
