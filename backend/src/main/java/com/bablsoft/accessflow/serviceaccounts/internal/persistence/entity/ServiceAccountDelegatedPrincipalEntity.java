package com.bablsoft.accessflow.serviceaccounts.internal.persistence.entity;

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
import java.util.UUID;

/**
 * A standing grant that lets a service account name a human in {@code X-AccessFlow-On-Behalf-Of}
 * (#874). It confers nothing — the agent's permissions stay its own — so there is nothing to
 * optimistically lock: rows are inserted and soft-revoked, never edited. FKs exist in the schema
 * but, as with {@link ServiceAccountEntity}, the module owns no JPA association into {@code core}.
 */
@Entity
@Table(name = "service_account_delegated_principals")
@Access(AccessType.FIELD)
@Getter
@Setter
@NoArgsConstructor
public class ServiceAccountDelegatedPrincipalEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "service_account_user_id", nullable = false)
    private UUID serviceAccountUserId;

    @Column(name = "principal_user_id", nullable = false)
    private UUID principalUserId;

    @Column(name = "granted_by")
    private UUID grantedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    /** Live = unrevoked and not yet expired at {@code now}; the header check and the view share it. */
    public boolean isLiveAt(Instant now) {
        return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }
}
