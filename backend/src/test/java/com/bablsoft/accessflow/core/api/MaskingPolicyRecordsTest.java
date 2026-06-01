package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingPolicyRecordsTest {

    @Test
    void resolvedColumnMaskNullParamsBecomesEmpty() {
        var mask = new ResolvedColumnMask(UUID.randomUUID(), "c", MaskingStrategy.FULL, null);
        assertThat(mask.params()).isEmpty();
    }

    @Test
    void resolvedColumnMaskParamsAreDefensivelyCopied() {
        var mutable = new HashMap<String, String>();
        mutable.put("visible_suffix", "4");
        var mask = new ResolvedColumnMask(UUID.randomUUID(), "c", MaskingStrategy.PARTIAL, mutable);
        mutable.clear();
        assertThat(mask.params()).containsEntry("visible_suffix", "4");
    }

    @Test
    void maskingPolicyViewNullCollectionsBecomeEmpty() {
        var view = new MaskingPolicyView(UUID.randomUUID(), UUID.randomUUID(), "c",
                MaskingStrategy.HASH, null, null, null, null, true, Instant.EPOCH, Instant.EPOCH);

        assertThat(view.strategyParams()).isEmpty();
        assertThat(view.revealToRoles()).isEmpty();
        assertThat(view.revealToGroupIds()).isEmpty();
        assertThat(view.revealToUserIds()).isEmpty();
    }

    @Test
    void maskingPolicyViewRetainsValues() {
        var groupId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var view = new MaskingPolicyView(UUID.randomUUID(), UUID.randomUUID(), "public.users.email",
                MaskingStrategy.EMAIL, Map.of("k", "v"), List.of("ADMIN"), List.of(groupId),
                List.of(userId), false, Instant.EPOCH, Instant.EPOCH);

        assertThat(view.strategy()).isEqualTo(MaskingStrategy.EMAIL);
        assertThat(view.revealToRoles()).containsExactly("ADMIN");
        assertThat(view.revealToGroupIds()).containsExactly(groupId);
        assertThat(view.revealToUserIds()).containsExactly(userId);
        assertThat(view.enabled()).isFalse();
    }
}
