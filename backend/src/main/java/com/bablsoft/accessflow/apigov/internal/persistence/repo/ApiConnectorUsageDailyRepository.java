package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorUsageDailyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ApiConnectorUsageDailyRepository extends JpaRepository<ApiConnectorUsageDailyEntity, UUID> {

    /**
     * The one write path (#971): adds the deltas to the {@code (connector, user, usageDate)} row,
     * creating it on first use. {@code id} is used only when the row is inserted. The caller
     * computes {@code usageDate} as a UTC day from the injected {@code Clock}.
     */
    @Modifying
    @Query(value = """
            INSERT INTO api_connector_usage_daily
                (id, organization_id, connector_id, user_id, usage_date,
                 calls, failed_calls, throttled_calls, spend_micros, updated_at)
            VALUES (:id, :organizationId, :connectorId, :userId, CAST(:usageDate AS date),
                    :calls, :failedCalls, :throttledCalls, :spendMicros, :now)
            ON CONFLICT (connector_id, user_id, usage_date) DO UPDATE SET
                calls = api_connector_usage_daily.calls + EXCLUDED.calls,
                failed_calls = api_connector_usage_daily.failed_calls + EXCLUDED.failed_calls,
                throttled_calls = api_connector_usage_daily.throttled_calls + EXCLUDED.throttled_calls,
                spend_micros = api_connector_usage_daily.spend_micros + EXCLUDED.spend_micros,
                updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    int accumulate(@Param("id") UUID id,
                   @Param("organizationId") UUID organizationId,
                   @Param("connectorId") UUID connectorId,
                   @Param("userId") UUID userId,
                   @Param("usageDate") LocalDate usageDate,
                   @Param("calls") long calls,
                   @Param("failedCalls") long failedCalls,
                   @Param("throttledCalls") long throttledCalls,
                   @Param("spendMicros") long spendMicros,
                   @Param("now") Instant now);

    /** Spend on a connector from {@code fromDate} (inclusive) onward, across all users; 0 when none. */
    @Query(value = """
            SELECT COALESCE(SUM(spend_micros), 0)
            FROM api_connector_usage_daily
            WHERE connector_id = :connectorId
              AND usage_date >= CAST(:fromDate AS date)
            """, nativeQuery = true)
    long sumSpendMicros(@Param("connectorId") UUID connectorId, @Param("fromDate") LocalDate fromDate);

    List<ApiConnectorUsageDailyEntity> findByConnectorIdAndUsageDateBetweenOrderByUsageDateAsc(
            UUID connectorId, LocalDate from, LocalDate to);

    List<ApiConnectorUsageDailyEntity> findByConnectorIdAndUserIdAndUsageDateBetweenOrderByUsageDateAsc(
            UUID connectorId, UUID userId, LocalDate from, LocalDate to);
}
