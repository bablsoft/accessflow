package com.bablsoft.accessflow.core.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.QueryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "query_estimates")
@Getter
@Setter
@NoArgsConstructor
public class QueryEstimateEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "query_request_id", nullable = false)
    private QueryRequestEntity queryRequest;

    @Column(name = "engine_id", length = 64)
    private String engineId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "query_type", columnDefinition = "query_type")
    private QueryType queryType;

    @Column(nullable = false)
    private boolean supported = false;

    @Column(name = "estimated_rows")
    private Long estimatedRows;

    @Column(name = "affected_row_count")
    private Long affectedRowCount;

    @Column(name = "scan_type", length = 128)
    private String scanType;

    @Column(name = "estimated_cost")
    private Double estimatedCost;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String plan;

    @Column(name = "raw_plan", columnDefinition = "text")
    private String rawPlan;

    @Column(name = "unsupported_reason", length = 500)
    private String unsupportedReason;

    @Column(nullable = false)
    private boolean failed = false;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
