package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RowLimitRecordsTest {

    @Test
    void appliedRowLimitTightensOnlyDownwards() {
        var limit = new AppliedRowLimit(200, Set.of());

        assertThat(limit.tighten(null)).isEqualTo(200);
        assertThat(limit.tighten(1000)).isEqualTo(200);
        assertThat(limit.tighten(50)).isEqualTo(50);
    }

    @Test
    void appliedRowLimitRejectsNonPositiveCapAndCopiesIds() {
        assertThatThrownBy(() -> new AppliedRowLimit(0, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        var ids = new HashSet<UUID>(Set.of(UUID.randomUUID()));
        var limit = new AppliedRowLimit(1, ids);
        ids.clear();
        assertThat(limit.policyIds()).hasSize(1);
        assertThat(new AppliedRowLimit(1, null).policyIds()).isEmpty();
    }

    @Test
    void viewNullCollectionsBecomeEmpty() {
        var view = new RowLimitPolicyView(UUID.randomUUID(), UUID.randomUUID(), null, "orders", 5,
                null, null, null, true, null, null);

        assertThat(view.appliesToRoles()).isEmpty();
        assertThat(view.appliesToGroupIds()).isEmpty();
        assertThat(view.appliesToUserIds()).isEmpty();
    }

    @Test
    void exceptionsCarryMessages() {
        var id = UUID.randomUUID();
        assertThat(new RowLimitPolicyNotFoundException(id)).hasMessageContaining(id.toString());
        assertThat(new IllegalRowLimitPolicyException("bad")).hasMessage("bad");
    }
}
