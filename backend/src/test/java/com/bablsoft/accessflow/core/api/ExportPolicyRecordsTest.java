package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExportPolicyRecordsTest {

    @Test
    void viewNullCollectionsBecomeEmpty() {
        var view = new ExportPolicyView(UUID.randomUUID(), UUID.randomUUID(),
                ExportPolicyMode.ALLOW, null, null, null, null, null, true, Instant.EPOCH,
                Instant.EPOCH);

        assertThat(view.denyClassifications()).isEmpty();
        assertThat(view.appliesToRoles()).isEmpty();
        assertThat(view.appliesToGroupIds()).isEmpty();
        assertThat(view.appliesToUserIds()).isEmpty();
    }

    @Test
    void viewRetainsValues() {
        var groupId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var view = new ExportPolicyView(UUID.randomUUID(), UUID.randomUUID(),
                ExportPolicyMode.ROW_CAP, 250, List.of(DataClassification.PII),
                List.of("ANALYST"), List.of(groupId), List.of(userId), false, Instant.EPOCH,
                Instant.EPOCH);

        assertThat(view.mode()).isEqualTo(ExportPolicyMode.ROW_CAP);
        assertThat(view.rowCap()).isEqualTo(250);
        assertThat(view.denyClassifications()).containsExactly(DataClassification.PII);
        assertThat(view.appliesToRoles()).containsExactly("ANALYST");
        assertThat(view.appliesToGroupIds()).containsExactly(groupId);
        assertThat(view.appliesToUserIds()).containsExactly(userId);
        assertThat(view.enabled()).isFalse();
    }

    @Test
    void viewCollectionsAreDefensivelyCopied() {
        var mutable = new ArrayList<>(List.of("ANALYST"));
        var view = new ExportPolicyView(UUID.randomUUID(), UUID.randomUUID(),
                ExportPolicyMode.WATERMARK, null, null, mutable, null, null, true, Instant.EPOCH,
                Instant.EPOCH);

        mutable.clear();

        assertThat(view.appliesToRoles()).containsExactly("ANALYST");
    }
}
