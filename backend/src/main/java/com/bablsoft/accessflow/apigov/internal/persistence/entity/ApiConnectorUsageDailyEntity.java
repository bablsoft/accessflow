package com.bablsoft.accessflow.apigov.internal.persistence.entity;

import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Daily per-user usage rollup of one API connector (#971). Rows are written only by
 * {@code ApiConnectorUsageDailyRepository.accumulate}, an atomic upsert that adds deltas, so the
 * entity is a read model and carries no {@code @Version}.
 */
@Entity
@Table(name = "api_connector_usage_daily")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class ApiConnectorUsageDailyEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "connector_id", nullable = false, updatable = false)
    private UUID connectorId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "usage_date", nullable = false, updatable = false)
    private LocalDate usageDate;

    @Column(name = "calls", nullable = false)
    private long calls;

    @Column(name = "failed_calls", nullable = false)
    private long failedCalls;

    @Column(name = "throttled_calls", nullable = false)
    private long throttledCalls;

    @Column(name = "spend_micros", nullable = false)
    private long spendMicros;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
