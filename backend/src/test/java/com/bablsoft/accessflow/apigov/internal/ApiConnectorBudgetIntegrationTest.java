package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.api.ApiUsageOutcome;
import com.bablsoft.accessflow.apigov.events.ApiConnectorBudgetExceededEvent;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorUsageDailyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #973 against a real Postgres: the once-per-UTC-month budget notification guard is one conditional
 * UPDATE, so crossing the budget twice in a month publishes once and the next month publishes again.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ApiConnectorBudgetIntegrationTest {

    @Autowired private ApiConnectorRepository connectorRepository;
    @Autowired private ApiConnectorUsageDailyRepository usageRepository;
    @Autowired private EffectiveApiConnectorPermissionResolver permissionResolver;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<Object> published = new ArrayList<>();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-10T12:00:00Z"));
    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private DefaultApiConnectorUsageService service;
    private ApiConnectorEntity connector;

    @BeforeEach
    void setUp() {
        service = new DefaultApiConnectorUsageService(connectorRepository, usageRepository,
                permissionResolver, published::add, clock);
        var c = new ApiConnectorEntity();
        c.setId(UUID.randomUUID());
        c.setOrganizationId(orgId);
        c.setName("budget-" + UUID.randomUUID());
        c.setProtocol(ApiProtocol.REST);
        c.setBaseUrl("https://api.test");
        c.setCostPerCallMicros(4_000L);
        c.setMonthlyBudgetMicros(10_000L);
        connector = connectorRepository.saveAndFlush(c);
    }

    private void call() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                service.accrue(connector.getId(), orgId, userId, ApiUsageOutcome.EXECUTED, 1));
    }

    private long budgetEvents() {
        return published.stream().filter(ApiConnectorBudgetExceededEvent.class::isInstance).count();
    }

    @Test
    void crossingTheBudgetTwiceInAMonthPublishesOnceAndANewMonthPublishesAgain() {
        call();
        call();
        assertThat(budgetEvents()).isZero();

        call(); // 12 000 ≥ 10 000
        assertThat(published).singleElement().isEqualTo(
                new ApiConnectorBudgetExceededEvent(connector.getId(), orgId, 12_000L, 10_000L));
        call(); // still over, same month
        assertThat(budgetEvents()).isEqualTo(1);
        assertThat(connectorRepository.findById(connector.getId()).orElseThrow()
                .getBudgetExceededNotifiedMonth()).isEqualTo(LocalDate.parse("2026-11-01"));
        assertThat(service.budgetUsedPercent(connector.getId())).isEqualTo(160);

        clock.set(Instant.parse("2026-12-01T00:00:01Z"));
        assertThat(service.budgetUsedPercent(connector.getId())).isZero();
        call();
        call();
        call();
        assertThat(budgetEvents()).isEqualTo(2);
        assertThat(connectorRepository.findById(connector.getId()).orElseThrow()
                .getBudgetExceededNotifiedMonth()).isEqualTo(LocalDate.parse("2026-12-01"));

        var usage = service.usage(connector.getId(), orgId, null, LocalDate.parse("2026-11-30"),
                LocalDate.parse("2026-12-01"));
        assertThat(usage.days()).hasSize(2);
        assertThat(usage.days().get(0).calls()).isZero();
        assertThat(usage.days().get(1).spendMicros()).isEqualTo(12_000L);
        assertThat(usage.monthToDateSpendMicros()).isEqualTo(12_000L);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant next) {
            this.instant = next;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
