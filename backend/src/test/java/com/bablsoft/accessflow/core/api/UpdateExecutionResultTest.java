package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateExecutionResultTest {

    @Test
    void legacyConstructorsDefaultPolicyIdsToEmptyAndEffectiveSqlToNull() {
        var twoArg = new UpdateExecutionResult(3L, Duration.ZERO);
        var threeArg = new UpdateExecutionResult(3L, Duration.ZERO, null);

        assertThat(twoArg.appliedRowSecurityPolicyIds()).isEmpty();
        assertThat(twoArg.effectiveSql()).isNull();
        assertThat(threeArg.appliedRowSecurityPolicyIds()).isEmpty();
        assertThat(threeArg.effectiveSql()).isNull();
    }

    @Test
    void retainsEffectiveSqlAndPolicyIds() {
        var id = UUID.randomUUID();

        var result = new UpdateExecutionResult(1L, Duration.ZERO, Set.of(id),
                "DELETE FROM t WHERE region = ?");

        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(id);
        assertThat(result.effectiveSql()).isEqualTo("DELETE FROM t WHERE region = ?");
    }
}
