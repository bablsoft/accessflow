package com.bablsoft.accessflow.schemachange.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.QueryType;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
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
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

import java.time.Instant;
import java.util.UUID;

/**
 * One ordered statement of a change set (#878). Immutable once written — replacement is
 * delete-then-reinsert, so the row carries no {@code @Version} and no {@code updated_at}.
 */
@Entity
@Table(name = "schema_change_set_statements")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class SchemaChangeSetStatementEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "change_set_id", nullable = false)
    private SchemaChangeSetEntity changeSet;

    @Column(name = "sequence_order", nullable = false)
    private int sequenceOrder;

    @Column(name = "sql_text", nullable = false, columnDefinition = "TEXT")
    private String sqlText;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "query_type", nullable = false, columnDefinition = "query_type")
    private QueryType queryType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
