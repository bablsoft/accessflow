package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RowSecurityProxyRecordsTest {

    @Test
    void directiveDefensivelyCopiesValues() {
        var mutable = new ArrayList<Object>(List.of("EU"));
        var directive = new RowSecurityDirective(UUID.randomUUID(), "orders", "region",
                RowSecurityOperator.EQUALS, mutable);
        mutable.clear();
        assertThat(directive.values()).containsExactly("EU");
    }

    @Test
    void directiveNullValuesBecomeEmpty() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "orders", "region",
                RowSecurityOperator.EQUALS, null);
        assertThat(directive.values()).isEmpty();
    }

    @Test
    void directiveRejectsNullRequiredFields() {
        assertThatThrownBy(() -> new RowSecurityDirective(null, "orders", "region",
                RowSecurityOperator.EQUALS, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RowSecurityDirective(UUID.randomUUID(), null, "region",
                RowSecurityOperator.EQUALS, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RowSecurityDirective(UUID.randomUUID(), "orders", null,
                RowSecurityOperator.EQUALS, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RowSecurityDirective(UUID.randomUUID(), "orders", "region",
                null, List.of())).isInstanceOf(NullPointerException.class);
    }

    @Test
    void selectResultWithRowSecurityPolicyIdsAttachesIds() {
        var base = new SelectExecutionResult(List.of(), List.of(), 0, false, Duration.ZERO);
        var id = UUID.randomUUID();

        var withIds = base.withRowSecurityPolicyIds(Set.of(id));

        assertThat(base.appliedRowSecurityPolicyIds()).isEmpty();
        assertThat(withIds.appliedRowSecurityPolicyIds()).containsExactly(id);
    }

    @Test
    void selectResultMaskingConvenienceConstructorDefaultsRowSecurityEmpty() {
        var id = UUID.randomUUID();
        var result = new SelectExecutionResult(List.of(), List.of(), 0, false, Duration.ZERO,
                Set.of(id));
        assertThat(result.appliedMaskingPolicyIds()).containsExactly(id);
        assertThat(result.appliedRowSecurityPolicyIds()).isEmpty();
    }

    @Test
    void updateResultCarriesRowSecurityIds() {
        var id = UUID.randomUUID();
        var result = new UpdateExecutionResult(5, Duration.ZERO, Set.of(id));
        assertThat(result.rowsAffected()).isEqualTo(5);
        assertThat(result.appliedRowSecurityPolicyIds()).containsExactly(id);

        var legacy = new UpdateExecutionResult(2, Duration.ZERO);
        assertThat(legacy.appliedRowSecurityPolicyIds()).isEmpty();
    }

    @Test
    void unrewritableExceptionCarriesMessage() {
        var ex = new UnrewritableRowSecurityException("nope");
        assertThat(ex.getMessage()).isEqualTo("nope");
    }
}
