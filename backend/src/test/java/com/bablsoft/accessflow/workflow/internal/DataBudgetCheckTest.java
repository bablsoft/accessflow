package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DataBudgetBreachAction;
import com.bablsoft.accessflow.core.api.DataBudgetConsumption;
import com.bablsoft.accessflow.core.api.DataBudgetStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DataBudgetCheckTest {

    private final UUID datasourceId = UUID.randomUUID();

    @Test
    void noStandingMeansNoCheck() {
        assertThat(DataBudgetCheck.of(null)).isNull();
        assertThat(DataBudgetCheck.of(DataBudgetStatus.none(datasourceId))).isNull();
    }

    @Test
    void allowanceLeftDecidesNothing() {
        var check = DataBudgetCheck.of(status(DataBudgetBreachAction.REJECT, 40));

        assertThat(check.exhausted()).isFalse();
        assertThat(check.rejects()).isFalse();
        assertThat(check.forcesReview()).isFalse();
        assertThat(check.deciding()).isNull();
        assertThat(check.usedPercent()).isEqualTo(40d);
        assertThat(check.remainingRows()).isEqualTo(60L);
        assertThat(check.remainingBytes()).isNull();
    }

    @Test
    void exhaustedBudgetsDecideByAction() {
        var reject = DataBudgetCheck.of(status(DataBudgetBreachAction.REJECT, 100));
        assertThat(reject.exhausted()).isTrue();
        assertThat(reject.rejects()).isTrue();
        assertThat(reject.forcesReview()).isFalse();
        assertThat(reject.deciding()).isNotNull();

        var review = DataBudgetCheck.of(status(DataBudgetBreachAction.REQUIRE_REVIEW, 120));
        assertThat(review.rejects()).isFalse();
        assertThat(review.forcesReview()).isTrue();
    }

    private DataBudgetStatus status(DataBudgetBreachAction action, long usedRows) {
        return new DataBudgetStatus(datasourceId, "ds", List.of(new DataBudgetConsumption(
                UUID.randomUUID(), "b", 100L, null, 60, action, null, usedRows, 0)));
    }
}
