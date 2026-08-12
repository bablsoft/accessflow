package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.GrantUsageRecommendation;
import com.bablsoft.accessflow.access.internal.persistence.entity.GrantUsageSummaryEntity;
import com.bablsoft.accessflow.core.api.GrantResourceKind;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GrantUsageViewMapperTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    private final GrantUsageViewMapper mapper = new GrantUsageViewMapper(new ObjectMapper());

    private static GrantUsageSummaryEntity entity() {
        var row = new GrantUsageSummaryEntity();
        row.setId(UUID.randomUUID());
        row.setOrganizationId(UUID.randomUUID());
        row.setResourceKind(GrantResourceKind.DATASOURCE);
        row.setResourceId(UUID.randomUUID());
        row.setResourceName("analytics");
        row.setPermissionId(UUID.randomUUID());
        row.setUserId(UUID.randomUUID());
        row.setUserEmail("dev@example.test");
        row.setUserDisplayName("Dev");
        row.setGrantedAt(NOW);
        row.setObservedSince(NOW);
        row.setRecommendation(GrantUsageRecommendation.ACTIVE);
        return row;
    }

    @Test
    void mapsEveryFieldOntoTheView() {
        var row = entity();
        row.setGrantedTargetCount(4);
        row.setUsedTargetCount(1);
        row.setUsageCount(9);
        row.setFirstUsedAt(NOW);
        row.setLastUsedAt(NOW);
        row.setExpiresAt(NOW);
        row.setUsedTargets("[\"public.users\"]");

        var view = mapper.toView(row);

        assertThat(view.id()).isEqualTo(row.getId());
        assertThat(view.resourceKind()).isEqualTo(GrantResourceKind.DATASOURCE);
        assertThat(view.resourceName()).isEqualTo("analytics");
        assertThat(view.userEmail()).isEqualTo("dev@example.test");
        assertThat(view.grantedTargetCount()).isEqualTo(4);
        assertThat(view.usedTargetCount()).isEqualTo(1);
        assertThat(view.usageCount()).isEqualTo(9);
        assertThat(view.usedTargets()).containsExactly("public.users");
        assertThat(view.recommendation()).isEqualTo(GrantUsageRecommendation.ACTIVE);
    }

    @Test
    void readsAnEmptyOrAbsentTargetList() {
        var row = entity();
        assertThat(mapper.readUsedTargets(row)).isEmpty();
        row.setUsedTargets(null);
        assertThat(mapper.readUsedTargets(row)).isEmpty();
        row.setUsedTargets("  ");
        assertThat(mapper.readUsedTargets(row)).isEmpty();
    }

    /**
     * The summary is a derived cache the next tick rewrites, so unreadable JSON must degrade to
     * empty rather than fail a report page.
     */
    @Test
    void degradesUnreadableTargetsToEmptyRatherThanThrowing() {
        var row = entity();
        row.setUsedTargets("{not json");
        assertThat(mapper.readUsedTargets(row)).isEmpty();
        row.setUsedTargets("{\"a\":1}");
        assertThat(mapper.readUsedTargets(row)).isEmpty();
        row.setUsedTargets("[\"ok\", 7, null]");
        assertThat(mapper.readUsedTargets(row)).containsExactly("ok");
    }

    @Test
    void writesTheTargetListAsAJsonArray() {
        assertThat(mapper.writeUsedTargets(List.of("a", "b"))).isEqualTo("[\"a\",\"b\"]");
        assertThat(mapper.writeUsedTargets(null)).isEqualTo("[]");
    }
}
