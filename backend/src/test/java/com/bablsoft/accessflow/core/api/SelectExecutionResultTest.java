package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SelectExecutionResultTest {

    @Test
    void legacyConstructorDefaultsAppliedPolicyIdsToEmpty() {
        var result = new SelectExecutionResult(List.of(), List.of(), 0L, false, Duration.ZERO);
        assertThat(result.appliedMaskingPolicyIds()).isEmpty();
    }

    @Test
    void nullAppliedPolicyIdsBecomesEmpty() {
        var result = new SelectExecutionResult(List.of(), List.of(), 0L, false, Duration.ZERO, null);
        assertThat(result.appliedMaskingPolicyIds()).isEmpty();
    }

    @Test
    void retainsProvidedAppliedPolicyIds() {
        var id = UUID.randomUUID();
        var result = new SelectExecutionResult(List.of(), List.of(), 0L, false, Duration.ZERO,
                Set.of(id));
        assertThat(result.appliedMaskingPolicyIds()).containsExactly(id);
    }

    @Test
    void legacyConstructorsDefaultTruncatedReasonToNull() {
        assertThat(new SelectExecutionResult(List.of(), List.of(), 0L, true, Duration.ZERO)
                .truncatedReason()).isNull();
        assertThat(new SelectExecutionResult(List.of(), List.of(), 0L, true, Duration.ZERO,
                Set.of()).truncatedReason()).isNull();
        assertThat(new SelectExecutionResult(List.of(), List.of(), 0L, true, Duration.ZERO,
                Set.of(), Set.of()).truncatedReason()).isNull();
    }

    @Test
    void withRowSecurityPolicyIdsPreservesTruncatedReason() {
        var result = new SelectExecutionResult(List.of(), List.of(), 0L, true, Duration.ZERO,
                Set.of(), Set.of(), SelectExecutionResult.TRUNCATED_BYTE_LIMIT);
        var id = UUID.randomUUID();

        var withIds = result.withRowSecurityPolicyIds(Set.of(id));

        assertThat(withIds.appliedRowSecurityPolicyIds()).containsExactly(id);
        assertThat(withIds.truncatedReason())
                .isEqualTo(SelectExecutionResult.TRUNCATED_BYTE_LIMIT);
    }

    @Test
    void legacyConstructorsDefaultEffectiveSqlToNull() {
        assertThat(new SelectExecutionResult(List.of(), List.of(), 0L, true, Duration.ZERO)
                .effectiveSql()).isNull();
        assertThat(new SelectExecutionResult(List.of(), List.of(), 0L, true, Duration.ZERO,
                Set.of(), Set.of(), null).effectiveSql()).isNull();
    }

    @Test
    void withEffectiveSqlAndWithRowSecurityPolicyIdsPreserveEachOther() {
        var id = UUID.randomUUID();
        var result = new SelectExecutionResult(List.of(), List.of(), 0L, true, Duration.ZERO,
                Set.of(), Set.of(), SelectExecutionResult.TRUNCATED_ROW_LIMIT)
                .withEffectiveSql("SELECT * FROM (SELECT * FROM t WHERE r = ?) t")
                .withRowSecurityPolicyIds(Set.of(id));

        assertThat(result.effectiveSql()).isEqualTo("SELECT * FROM (SELECT * FROM t WHERE r = ?) t");
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(id);
        assertThat(result.truncatedReason()).isEqualTo(SelectExecutionResult.TRUNCATED_ROW_LIMIT);
    }

    @Test
    void byteAccountingCopiesPreserveEveryOtherField() {
        var id = UUID.randomUUID();
        List<List<Object>> rows = List.of(List.of(1), List.of(2), List.of(3));
        var result = new SelectExecutionResult(List.of(), rows, 3L, false, Duration.ZERO,
                Set.of(id), Set.of(), null, "SELECT 1");
        assertThat(result.resultBytes()).isZero();

        var measured = result.withResultBytes(120L);
        assertThat(measured.resultBytes()).isEqualTo(120L);
        assertThat(measured.appliedMaskingPolicyIds()).containsExactly(id);
        assertThat(measured.effectiveSql()).isEqualTo("SELECT 1");

        var trimmed = measured.truncatedTo(2, SelectExecutionResult.TRUNCATED_DATA_BUDGET, 80L);
        assertThat(trimmed.rows()).hasSize(2);
        assertThat(trimmed.rowCount()).isEqualTo(2);
        assertThat(trimmed.truncated()).isTrue();
        assertThat(trimmed.truncatedReason()).isEqualTo(SelectExecutionResult.TRUNCATED_DATA_BUDGET);
        assertThat(trimmed.resultBytes()).isEqualTo(80L);

        var relabeled = measured.withTruncatedReason(SelectExecutionResult.TRUNCATED_ROW_LIMIT);
        assertThat(relabeled.truncatedReason()).isEqualTo(SelectExecutionResult.TRUNCATED_ROW_LIMIT);
        assertThat(relabeled.resultBytes()).isEqualTo(120L);
        assertThat(relabeled.withEffectiveSql("x").resultBytes()).isEqualTo(120L);
        assertThat(relabeled.withRowSecurityPolicyIds(Set.of(id)).resultBytes()).isEqualTo(120L);
    }
}
