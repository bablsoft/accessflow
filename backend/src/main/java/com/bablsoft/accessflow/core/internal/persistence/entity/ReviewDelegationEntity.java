package com.bablsoft.accessflow.core.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.DelegationScopeKind;
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
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

import java.time.Instant;
import java.util.UUID;

/**
 * An out-of-office reviewer delegation (#622). While the window is open and the row is not revoked,
 * {@code delegateId} is an eligible approver everywhere {@code delegatorId} was.
 *
 * <p>The delegator's role name is deliberately not stored — it is resolved by joining users at
 * lookup time, so a role change mid-window takes effect immediately.
 */
@Entity
@Table(name = "review_delegations")
@Getter
@Setter
@NoArgsConstructor
public class ReviewDelegationEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "delegator_id", nullable = false)
    private UUID delegatorId;

    @Column(name = "delegate_id", nullable = false)
    private UUID delegateId;

    /** Null together with {@link #scopeId} for an unrestricted delegation. */
    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "scope_kind", columnDefinition = "review_delegation_scope_kind")
    private DelegationScopeKind scopeKind;

    /**
     * Bare reference, polymorphic over datasources and api_connectors by {@link #scopeKind}, so no
     * single FK can express it. Validated on write; a dangling id fails closed on read.
     */
    @Column(name = "scope_id")
    private UUID scopeId;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    /** Soft revocation — the row is never deleted, so decisions taken under it keep their evidence. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
