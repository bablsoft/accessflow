package com.bablsoft.accessflow.dashboard.internal.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A user's opt-in for the scheduled weekly-digest email (AF-498). One row per user (unique
 * {@code user_id}); {@code last_sent_at} is stamped by {@code WeeklyDigestJob} so the digest fires at
 * most once per configured period regardless of poll cadence or restarts.
 */
@Entity
@Table(name = "dashboard_digest_subscription")
@Getter
@Setter
@NoArgsConstructor
public class DashboardDigestSubscriptionEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "last_sent_at")
    private Instant lastSentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;
}
