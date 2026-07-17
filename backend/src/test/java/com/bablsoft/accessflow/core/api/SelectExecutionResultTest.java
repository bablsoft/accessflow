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
}
