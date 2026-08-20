package com.bablsoft.accessflow.audit.internal.persistence.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.bablsoft.accessflow.audit.api.AuditSinkType;
import jakarta.persistence.Access;
import jakarta.persistence.AccessType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
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
 * An external audit sink (#628) plus its durable delivery cursor and health. The cursor is a
 * {@code (cursor_created_at, cursor_id)} keyset over {@code audit_log} — {@code created_at} is
 * not unique there, so a bare timestamp cursor would replay or skip rows. Cursor and health are
 * updated atomically with the row under {@link Version optimistic locking}, which also makes a
 * lost drain-vs-admin-update race fail loudly instead of silently clobbering the cursor.
 */
@Entity
@Table(name = "audit_sinks")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class AuditSinkEntity {

    /** Nil-UUID cursor id: "start of the instant held in {@code cursorCreatedAt}". */
    public static final UUID CURSOR_ID_FLOOR = new UUID(0L, 0L);

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false, columnDefinition = "audit_sink_type")
    private AuditSinkType type;

    @JsonIgnore
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false, columnDefinition = "jsonb")
    private String configJson = "{}";

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "cursor_created_at", nullable = false)
    private Instant cursorCreatedAt = Instant.EPOCH;

    @Column(name = "cursor_id", nullable = false)
    private UUID cursorId = CURSOR_ID_FLOOR;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
