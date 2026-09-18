package com.bablsoft.accessflow.security.internal.persistence.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_keys")
@Getter
@Setter
@NoArgsConstructor
public class ApiKeyEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "key_prefix", nullable = false, length = 16)
    private String keyPrefix;

    @JsonIgnore
    @Column(name = "key_hash", nullable = false, length = 128, unique = true)
    private String keyHash;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /**
     * True on the one key the bootstrap reconciler declared for a service account (#871). Such a
     * key can be neither revoked nor rotated: {@code importOrUpdate} clears {@code revoked_at} on
     * every changed reconcile, so a revoke would only appear to succeed until the next restart.
     */
    @Column(name = "bootstrap_declared", nullable = false)
    private boolean bootstrapDeclared;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
