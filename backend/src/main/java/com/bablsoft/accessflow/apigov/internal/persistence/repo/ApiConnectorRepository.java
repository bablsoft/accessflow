package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.apigov.api.ApiConnectorHealthStatus;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiConnectorRepository extends JpaRepository<ApiConnectorEntity, UUID> {

    Optional<ApiConnectorEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<ApiConnectorEntity> findByOrganizationIdAndActiveTrueOrderByNameAsc(UUID organizationId);

    /**
     * Every connector in the org, active or not — the review-queue reach (#622) must still cover
     * requests submitted before a connector was deactivated.
     */
    List<ApiConnectorEntity> findAllByOrganizationId(UUID organizationId);

    Page<ApiConnectorEntity> findByOrganizationId(UUID organizationId, Pageable pageable);

    boolean existsByOrganizationIdAndName(UUID organizationId, String name);

    /** Onboarding checklist (AF-898) — active or not, a connector means the step was done. */
    boolean existsByOrganizationId(UUID organizationId);

    /** Row-locked read that serializes a probe's write against admin edits and overlapping probes (#894). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ApiConnectorEntity c where c.id = :id")
    Optional<ApiConnectorEntity> findByIdForUpdate(@Param("id") UUID id);

    /** Active connectors opted into the scheduled health probe (#894), least recently probed first. */
    @Query("""
            select new com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorHealthCandidate(
                c.id, c.organizationId, c.healthCheckIntervalMinutes, c.lastProbeAt)
            from ApiConnectorEntity c
            where c.active = true and c.healthCheckIntervalMinutes is not null
            order by c.lastProbeAt asc nulls first
            """)
    List<ApiConnectorHealthCandidate> findHealthCandidates();

    /**
     * Stamps a scheduled probe's outcome without a whole-row UPDATE, so a probe racing an admin edit
     * can never write back stale connector config (the row carries no {@code @Version}).
     */
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("""
            update ApiConnectorEntity c set c.lastProbeStatus = :status, c.lastProbeAt = :at,
                c.consecutiveProbeFailures = :failures
            where c.id = :id
            """)
    int updateProbeOutcome(@Param("id") UUID id, @Param("status") ApiConnectorHealthStatus status,
                           @Param("at") Instant at, @Param("failures") int failures);

    /** Forgets the probe state when an admin turns monitoring off (#894). */
    default int resetProbeState(UUID id) {
        return updateProbeOutcome(id, ApiConnectorHealthStatus.UNKNOWN, null, 0);
    }

    /**
     * The one-shot-per-month budget notification guard (#973): stamps {@code month} unless it is
     * already the stamped month. Returns 1 for the one caller that won the month, 0 for everyone
     * else, so a month notifies at most once even across replicas. Two concurrent calls that cross
     * the budget together may each miss the other's uncommitted spend; the notification then fires
     * on the next priced call instead.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE api_connectors SET budget_exceeded_notified_month = CAST(:month AS date)
            WHERE id = :id
              AND budget_exceeded_notified_month IS DISTINCT FROM CAST(:month AS date)
            """, nativeQuery = true)
    int markBudgetExceededNotified(@Param("id") UUID id, @Param("month") LocalDate month);
}
