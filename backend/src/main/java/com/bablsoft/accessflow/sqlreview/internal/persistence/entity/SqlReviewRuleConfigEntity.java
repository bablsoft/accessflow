package com.bablsoft.accessflow.sqlreview.internal.persistence.entity;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
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
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * One rule's severity and parameters inside a ruleset (#861). {@code (ruleset, rule_id)} is
 * unique; the row cascades away with its ruleset.
 */
@Entity
@Table(name = "sql_review_rule_configs")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class SqlReviewRuleConfigEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ruleset_id", nullable = false)
    private SqlReviewRulesetEntity ruleset;

    @Column(name = "rule_id", nullable = false, length = 100)
    private String ruleId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "sql_review_severity")
    private SqlReviewSeverity severity;

    /** Rule-specific parameters as a JSON object, or null when the rule takes none. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String params;

    @Version
    @Column(nullable = false)
    private long version;
}
