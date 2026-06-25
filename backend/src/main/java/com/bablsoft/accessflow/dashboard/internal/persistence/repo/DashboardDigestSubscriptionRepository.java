package com.bablsoft.accessflow.dashboard.internal.persistence.repo;

import com.bablsoft.accessflow.dashboard.internal.persistence.entity.DashboardDigestSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DashboardDigestSubscriptionRepository
        extends JpaRepository<DashboardDigestSubscriptionEntity, UUID> {

    Optional<DashboardDigestSubscriptionEntity> findByUserId(UUID userId);

    /**
     * Subscriptions that are enabled and have either never been sent or were last sent before
     * {@code before} — i.e. due for the next digest. Used by {@code WeeklyDigestJob}.
     */
    @Query("""
            SELECT s FROM DashboardDigestSubscriptionEntity s
            WHERE s.enabled = true
              AND (s.lastSentAt IS NULL OR s.lastSentAt < :before)
            ORDER BY s.lastSentAt ASC NULLS FIRST
            """)
    List<DashboardDigestSubscriptionEntity> findDue(@Param("before") Instant before);
}
