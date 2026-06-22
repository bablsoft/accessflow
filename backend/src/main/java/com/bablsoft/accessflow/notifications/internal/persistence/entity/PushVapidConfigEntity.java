package com.bablsoft.accessflow.notifications.internal.persistence.entity;

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
 * The single deployment-level VAPID keypair used to sign Web Push requests (AF-444). The public
 * key is exposed to browsers so they can subscribe; the private key is AES-256-GCM encrypted with
 * {@code ENCRYPTION_KEY} and never leaves the server. Auto-generated and persisted on first use
 * unless the {@code ACCESSFLOW_PUSH_VAPID_*} env overrides are supplied.
 */
@Entity
@Table(name = "push_vapid_config")
@Getter
@Setter
@NoArgsConstructor
public class PushVapidConfigEntity {

    @Id
    private UUID id;

    @Column(name = "public_key", nullable = false)
    private String publicKey;

    @JsonIgnore
    @Column(name = "private_key_encrypted", nullable = false)
    private String privateKeyEncrypted;

    @Column(name = "subject", nullable = false)
    private String subject;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
