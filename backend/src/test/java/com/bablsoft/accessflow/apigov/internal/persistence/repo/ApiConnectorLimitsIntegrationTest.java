package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.core.api.QueryStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #971 against a real Postgres under {@code ddl-auto=validate}: the connector limit and budget
 * columns with their CHECK constraints, the api_requests throttle/cost columns, and the
 * api_connector_usage_daily upsert with its ON DELETE CASCADE.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ApiConnectorLimitsIntegrationTest {

    @Autowired private ApiConnectorRepository connectorRepository;
    @Autowired private ApiRequestRepository requestRepository;
    @Autowired private ApiConnectorUsageDailyRepository usageRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private final UUID orgId = UUID.randomUUID();

    private ApiConnectorEntity connector(Consumer<ApiConnectorEntity> customizer) {
        var c = new ApiConnectorEntity();
        c.setId(UUID.randomUUID());
        c.setOrganizationId(orgId);
        c.setName("conn-" + UUID.randomUUID());
        c.setProtocol(ApiProtocol.REST);
        c.setBaseUrl("https://api.test");
        customizer.accept(c);
        return c;
    }

    private void accumulate(UUID connectorId, UUID userId, LocalDate day, long calls, long failed,
                            long throttled, long spend) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                usageRepository.accumulate(UUID.randomUUID(), orgId, connectorId, userId, day,
                        calls, failed, throttled, spend, Instant.now()));
    }

    @Test
    void limitsRoundTripAndAnExistingStyleConnectorReadsUnlimited() {
        var limited = connectorRepository.saveAndFlush(connector(c -> {
            c.setCallsPerMinute(60);
            c.setCallsPerDay(10_000);
            c.setPerUserSharePercent(25);
            c.setCostPerCallMicros(1_500L);
            c.setMonthlyBudgetMicros(9_000_000L);
        }));
        var plain = connectorRepository.saveAndFlush(connector(c -> { }));

        var reloaded = connectorRepository.findById(limited.getId()).orElseThrow();
        assertThat(reloaded.getCallsPerMinute()).isEqualTo(60);
        assertThat(reloaded.getCallsPerDay()).isEqualTo(10_000);
        assertThat(reloaded.getPerUserSharePercent()).isEqualTo(25);
        assertThat(reloaded.getCostPerCallMicros()).isEqualTo(1_500L);
        assertThat(reloaded.getMonthlyBudgetMicros()).isEqualTo(9_000_000L);
        assertThat(reloaded.getBudgetExceededNotifiedMonth()).isNull();

        var unlimited = connectorRepository.findById(plain.getId()).orElseThrow();
        assertThat(unlimited.getCallsPerMinute()).isNull();
        assertThat(unlimited.getCallsPerDay()).isNull();
        assertThat(unlimited.getPerUserSharePercent()).isNull();
        assertThat(unlimited.getCostPerCallMicros()).isNull();
        assertThat(unlimited.getMonthlyBudgetMicros()).isNull();
    }

    @Test
    void checkConstraintsRejectOutOfRangeLimits() {
        assertThatThrownBy(() -> connectorRepository.saveAndFlush(connector(c -> c.setCallsPerMinute(0))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> connectorRepository.saveAndFlush(connector(c -> c.setCallsPerDay(-1))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> connectorRepository.saveAndFlush(connector(c -> c.setPerUserSharePercent(101))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> connectorRepository.saveAndFlush(connector(c -> c.setPerUserSharePercent(0))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> connectorRepository.saveAndFlush(connector(c -> c.setCostPerCallMicros(-1L))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> connectorRepository.saveAndFlush(connector(c -> c.setMonthlyBudgetMicros(0L))))
                .isInstanceOf(DataIntegrityViolationException.class);

        var free = connectorRepository.saveAndFlush(connector(c -> c.setCostPerCallMicros(0L)));
        assertThat(connectorRepository.findById(free.getId()).orElseThrow().getCostPerCallMicros()).isZero();
    }

    @Test
    void requestThrottleColumnsDefaultAndRoundTrip() {
        var request = new ApiRequestEntity();
        request.setId(UUID.randomUUID());
        request.setConnectorId(UUID.randomUUID());
        request.setOrganizationId(orgId);
        request.setSubmittedBy(UUID.randomUUID());
        request.setVerb("GET");
        request.setRequestPath("/v1/things");
        request.setStatus(QueryStatus.APPROVED);
        requestRepository.saveAndFlush(request);

        var fresh = requestRepository.findById(request.getId()).orElseThrow();
        assertThat(fresh.getThrottledUntil()).isNull();
        assertThat(fresh.getThrottleCount()).isZero();
        assertThat(fresh.getCostMicros()).isNull();

        var until = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        fresh.setThrottledUntil(until);
        fresh.setThrottleCount(2);
        fresh.setCostMicros(1_500L);
        requestRepository.saveAndFlush(fresh);

        var throttled = requestRepository.findById(request.getId()).orElseThrow();
        assertThat(throttled.getThrottledUntil()).isEqualTo(until);
        assertThat(throttled.getThrottleCount()).isEqualTo(2);
        assertThat(throttled.getCostMicros()).isEqualTo(1_500L);
    }

    @Test
    void upsertAccumulatesOnTheSameKeyAndSeparatesDays() {
        var connectorId = connectorRepository.saveAndFlush(connector(c -> { })).getId();
        var userId = UUID.randomUUID();
        var day = LocalDate.of(2026, 11, 9);

        accumulate(connectorId, userId, day, 1, 0, 0, 1_500);
        accumulate(connectorId, userId, day, 1, 1, 2, 1_500);
        accumulate(connectorId, userId, day.plusDays(1), 3, 0, 0, 4_500);
        accumulate(connectorId, UUID.randomUUID(), day, 1, 0, 0, 100);

        var rows = usageRepository.findByConnectorIdAndUserIdAndUsageDateBetweenOrderByUsageDateAsc(
                connectorId, userId, day, day.plusDays(1));
        assertThat(rows).hasSize(2);
        var first = rows.getFirst();
        assertThat(first.getUsageDate()).isEqualTo(day);
        assertThat(first.getCalls()).isEqualTo(2);
        assertThat(first.getFailedCalls()).isEqualTo(1);
        assertThat(first.getThrottledCalls()).isEqualTo(2);
        assertThat(first.getSpendMicros()).isEqualTo(3_000);
        assertThat(first.getOrganizationId()).isEqualTo(orgId);
        assertThat(rows.get(1).getUsageDate()).isEqualTo(day.plusDays(1));
        assertThat(rows.get(1).getCalls()).isEqualTo(3);

        assertThat(usageRepository.findByConnectorIdAndUsageDateBetweenOrderByUsageDateAsc(
                connectorId, day, day)).hasSize(2);
        assertThat(usageRepository.sumSpendMicros(connectorId, day)).isEqualTo(7_600);
        assertThat(usageRepository.sumSpendMicros(connectorId, day.plusDays(1))).isEqualTo(4_500);
        assertThat(usageRepository.sumSpendMicros(connectorId, day.plusDays(2))).isZero();
    }

    @Test
    void deletingTheConnectorCascadesItsRollup() {
        var connectorId = connectorRepository.saveAndFlush(connector(c -> { })).getId();
        var day = LocalDate.of(2026, 11, 9);
        accumulate(connectorId, UUID.randomUUID(), day, 1, 0, 0, 10);

        connectorRepository.deleteById(connectorId);
        connectorRepository.flush();

        assertThat(usageRepository.findByConnectorIdAndUsageDateBetweenOrderByUsageDateAsc(
                connectorId, day, day)).isEmpty();
    }
}
