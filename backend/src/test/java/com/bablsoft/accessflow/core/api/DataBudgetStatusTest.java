package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataBudgetStatusTest {

    private final UUID datasourceId = UUID.randomUUID();

    @Test
    void emptyStatusHasNoStanding() {
        var status = DataBudgetStatus.none(datasourceId);

        assertThat(status.isEmpty()).isTrue();
        assertThat(status.exhausted()).isFalse();
        assertThat(status.breachAction()).isNull();
        assertThat(status.decidingBudget()).isNull();
        assertThat(status.remainingRows()).isNull();
        assertThat(status.remainingBytes()).isNull();
        assertThat(status.usedPercent()).isNull();
        assertThat(new DataBudgetStatus(datasourceId, null, null).budgets()).isEmpty();
    }

    @Test
    void theMostConstrainedBudgetWins() {
        var rows = consumption(100L, null, 40, 0, DataBudgetBreachAction.REQUIRE_REVIEW);
        var bytes = consumption(null, 1_000L, 0, 900, DataBudgetBreachAction.REJECT);
        var status = new DataBudgetStatus(datasourceId, "ds", List.of(rows, bytes));

        assertThat(status.exhausted()).isFalse();
        assertThat(status.remainingRows()).isEqualTo(60L);
        assertThat(status.remainingBytes()).isEqualTo(100L);
        assertThat(status.usedPercent()).isEqualTo(90d);
    }

    @Test
    void rejectBeatsReviewAmongExhaustedBudgets() {
        var review = consumption(100L, null, 100, 0, DataBudgetBreachAction.REQUIRE_REVIEW);
        var reject = consumption(null, 10L, 0, 20, DataBudgetBreachAction.REJECT);
        var status = new DataBudgetStatus(datasourceId, "ds", List.of(review, reject));

        assertThat(status.exhausted()).isTrue();
        assertThat(status.breachAction()).isEqualTo(DataBudgetBreachAction.REJECT);
        assertThat(status.decidingBudget()).isEqualTo(reject);
        assertThat(status.remainingRows()).isZero();
        assertThat(status.remainingBytes()).isZero();
    }

    @Test
    void consumptionArithmetic() {
        var c = consumption(200L, 50L, 50, 100, DataBudgetBreachAction.REJECT);

        assertThat(c.usedPercent()).isEqualTo(200d);
        assertThat(c.exhausted()).isTrue();
        var more = c.plus(10, 5);
        assertThat(more.usedRows()).isEqualTo(60);
        assertThat(more.usedBytes()).isEqualTo(105);
        assertThat(consumption(null, null, 5, 5, DataBudgetBreachAction.REJECT).usedPercent()).isZero();
    }

    @Test
    void strictestAction() {
        assertThat(DataBudgetBreachAction.strictest(null, DataBudgetBreachAction.REQUIRE_REVIEW))
                .isEqualTo(DataBudgetBreachAction.REQUIRE_REVIEW);
        assertThat(DataBudgetBreachAction.strictest(DataBudgetBreachAction.REQUIRE_REVIEW, null))
                .isEqualTo(DataBudgetBreachAction.REQUIRE_REVIEW);
        assertThat(DataBudgetBreachAction.strictest(DataBudgetBreachAction.REQUIRE_REVIEW,
                DataBudgetBreachAction.REJECT)).isEqualTo(DataBudgetBreachAction.REJECT);
        assertThat(DataBudgetBreachAction.strictest(DataBudgetBreachAction.REQUIRE_REVIEW,
                DataBudgetBreachAction.REQUIRE_REVIEW)).isEqualTo(DataBudgetBreachAction.REQUIRE_REVIEW);
    }

    @Test
    void usageRecordValidatesAndClamps() {
        var record = new DataBudgetUsageRecord(UUID.randomUUID(), datasourceId, -5, -1,
                DataBudgetUsageSource.SAMPLE_DATA, null, null);
        assertThat(record.rowsRead()).isZero();
        assertThat(record.bytesRead()).isZero();
        assertThatThrownBy(() -> new DataBudgetUsageRecord(null, datasourceId, 1, 1,
                DataBudgetUsageSource.QUERY, null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void viewDefensivelyCopiesTargets() {
        var view = new DataBudgetView(UUID.randomUUID(), datasourceId, "n", 1L, null, 60,
                DataBudgetBreachAction.REJECT, null, null, null, null, true, null, null);
        assertThat(view.appliesToRoles()).isEmpty();
        assertThat(view.appliesToGroupIds()).isEmpty();
        assertThat(view.appliesToUserIds()).isEmpty();
    }

    @Test
    void exhaustedExceptionCarriesTheBudget() {
        var c = consumption(1L, null, 1, 0, DataBudgetBreachAction.REJECT);
        var ex = new DataBudgetExhaustedException("used up", c);
        assertThat(ex).hasMessage("used up");
        assertThat(ex.budget()).isEqualTo(c);
        assertThat(new DataBudgetNotFoundException(c.budgetId())).hasMessageContaining("not found");
        assertThat(new IllegalDataBudgetException("bad")).hasMessage("bad");
    }

    private DataBudgetConsumption consumption(Long maxRows, Long maxBytes, long usedRows,
                                              long usedBytes, DataBudgetBreachAction action) {
        return new DataBudgetConsumption(UUID.randomUUID(), "b", maxRows, maxBytes, 60, action, 80,
                usedRows, usedBytes);
    }
}
