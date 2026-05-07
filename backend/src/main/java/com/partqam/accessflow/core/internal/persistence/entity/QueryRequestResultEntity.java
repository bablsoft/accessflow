package com.partqam.accessflow.core.internal.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "query_request_results")
@Getter
@Setter
@NoArgsConstructor
public class QueryRequestResultEntity {

    @Id
    @Column(name = "query_request_id", nullable = false)
    private UUID queryRequestId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String columns = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String rows = "[]";

    @Column(name = "row_count", nullable = false)
    private long rowCount;

    @Column(nullable = false)
    private boolean truncated;

    @Column(name = "duration_ms", nullable = false)
    private int durationMs;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt = Instant.now();
}
