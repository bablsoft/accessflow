package com.bablsoft.accessflow.core.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.DataBudgetUsageSource;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "data_budget_usage")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class DataBudgetUsageEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "datasource_id", nullable = false, updatable = false)
    private UUID datasourceId;

    @Column(name = "rows_read", nullable = false, updatable = false)
    private long rowsRead;

    @Column(name = "bytes_read", nullable = false, updatable = false)
    private long bytesRead;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "source", nullable = false, updatable = false,
            columnDefinition = "data_budget_usage_source")
    private DataBudgetUsageSource source;

    @Column(name = "query_request_id", updatable = false)
    private UUID queryRequestId;

    @Column(name = "request_group_id", updatable = false)
    private UUID requestGroupId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;
}
