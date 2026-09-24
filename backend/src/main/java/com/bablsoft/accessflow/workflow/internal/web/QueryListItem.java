package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.ApplicationNameSource;
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
        boolean aiFailed,
        Instant scheduledFor,
        boolean recurring,
        UUID recurringParentId,
        Instant createdAt,
        /** The calling application (#938); null when unknown. */
        String applicationName,
        /** {@code API_KEY} (trustworthy) or {@code HEADER} (client-controlled). */
        ApplicationNameSource applicationNameSource) {

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
                view.aiFailed(),
                view.scheduledFor(),
                view.recurring(),
                view.recurringParentId(),
                view.createdAt(),
                view.applicationName(),
                view.applicationNameSource());
    }

    public record DatasourceRef(UUID id, String name) {
    }

    public record SubmitterRef(UUID id, String email, String displayName) {
    }
}
