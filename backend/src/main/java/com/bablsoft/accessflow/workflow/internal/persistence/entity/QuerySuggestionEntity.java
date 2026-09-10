package com.bablsoft.accessflow.workflow.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.QueryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One precomputed automatic query suggestion (#776) — a distinct approved query shape on one
 * datasource, with the frequency, recency and breadth evidence the ranking heuristic scores.
 *
 * <p>Rows are derived state, rewritten wholesale by {@code QuerySuggestionAggregationJob}. Nothing
 * here is authored by a user, and nothing here is consulted by any decision path.
 */
@Entity
@Table(name = "query_suggestions")
@Getter
@Setter
@NoArgsConstructor
public class QuerySuggestionEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "datasource_id", nullable = false)
    private UUID datasourceId;

    /** SHA-256 hex of the canonical form; the group key, narrow enough for a btree unique index. */
    @Column(name = "canonical_hash", nullable = false, length = 64)
    private String canonicalHash;

    /** Raw text of the most recent approved request in the group — what the editor loads. */
    @Column(name = "sql_text", nullable = false, columnDefinition = "TEXT")
    private String sqlText;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "query_type", nullable = false, columnDefinition = "query_type")
    private QueryType queryType;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "referenced_tables", nullable = false, columnDefinition = "text[]")
    private String[] referencedTables = new String[0];

    /**
     * Who ran this shape, capped by the aggregation. Read back only to derive the viewer's own
     * table affinity for the ranking overlap term; never serialised onto the wire.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "submitter_ids", nullable = false, columnDefinition = "uuid[]")
    private UUID[] submitterIds = new UUID[0];

    @Column(name = "approved_count", nullable = false)
    private int approvedCount;

    @Column(name = "distinct_submitter_count", nullable = false)
    private int distinctSubmitterCount;

    @Column(name = "first_submitted_at", nullable = false)
    private Instant firstSubmittedAt;

    @Column(name = "last_submitted_at", nullable = false)
    private Instant lastSubmittedAt;

    /** Stamp of the aggregation pass that wrote this row; rows older than the pass are swept. */
    @Column(name = "computed_at", nullable = false)
    private Instant computedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
