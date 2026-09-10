package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.workflow.api.AccessSource;
import com.bablsoft.accessflow.workflow.api.AccessSourceKind;
import com.bablsoft.accessflow.workflow.api.EffectiveAccessRow;
import com.bablsoft.accessflow.workflow.api.TableScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EffectiveAccessPageResponseTest {

    @Test
    void carriesEverySourceAndThePageCounters() {
        var userId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var sourceId = UUID.randomUUID();
        var expiry = Instant.parse("2026-10-01T00:00:00Z");
        var row = new EffectiveAccessRow(userId, "dana@example.com", "Dana", "ANALYST", true,
                TableScope.ALLOW_LISTED, expiry, true,
                List.of(new AccessSource(AccessSourceKind.GROUP_PERMISSION, sourceId, groupId,
                                "payments-oncall", true, TableScope.ALLOW_LISTED, "public", expiry,
                                false),
                        new AccessSource(AccessSourceKind.BREAK_GLASS, sourceId, null, null, false,
                                TableScope.ALL_TABLES, null, null, false)));

        var response = EffectiveAccessPageResponse.from(
                new PageResponse<>(List.of(row), 0, 20, 1, 1));

        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.totalPages()).isEqualTo(1);
        assertThat(response.content()).singleElement().satisfies(mapped -> {
            assertThat(mapped.userId()).isEqualTo(userId);
            assertThat(mapped.granted()).isTrue();
            assertThat(mapped.canBreakGlass()).isTrue();
            assertThat(mapped.effectiveExpiresAt()).isEqualTo(expiry);
            assertThat(mapped.sources()).extracting("kind").containsExactly(
                    AccessSourceKind.GROUP_PERMISSION, AccessSourceKind.BREAK_GLASS);
            assertThat(mapped.sources().get(0).groupName()).isEqualTo("payments-oncall");
            assertThat(mapped.sources().get(0).coveringAllowListEntry()).isEqualTo("public");
        });
    }

    @Test
    void anEmptyPageStaysEmpty() {
        var response = EffectiveAccessPageResponse.from(PageResponse.empty(2, 20));

        assertThat(response.content()).isEmpty();
        assertThat(response.page()).isEqualTo(2);
    }
}
