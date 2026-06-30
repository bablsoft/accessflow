package com.bablsoft.accessflow.lifecycle.internal.persistence.entity;

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

/**
 * Per-organization pseudonymization salt (AF-499). {@code saltEncrypted} is AES-256-GCM encrypted via
 * {@code CredentialEncryptionService} and never serialized. {@code version} is bumped on rotation;
 * values hashed under a previous salt stay hashed (irreversible by design).
 */
@Entity
@Table(name = "lifecycle_salt")
@Getter
@Setter
@NoArgsConstructor
public class LifecycleSaltEntity {

    @Id
    @Column(name = "organization_id")
    private UUID organizationId;

    @JsonIgnore
    @Column(name = "salt_encrypted", nullable = false, columnDefinition = "text")
    private String saltEncrypted;

    @Column(nullable = false)
    private int version = 1;

    @Column(name = "rotated_at", nullable = false)
    private Instant rotatedAt = Instant.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
