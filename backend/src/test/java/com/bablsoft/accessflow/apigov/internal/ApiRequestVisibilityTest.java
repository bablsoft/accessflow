package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.core.api.Permission;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRequestVisibilityTest {

    private final UUID submitter = UUID.randomUUID();

    private ApiRequestEntity request() {
        var e = new ApiRequestEntity();
        e.setSubmittedBy(submitter);
        return e;
    }

    @Test
    void submitterCanView() {
        assertThat(ApiRequestVisibility.canView(request(), submitter, Set.of())).isTrue();
    }

    @Test
    void viewAllHolderCanViewAnyone() {
        assertThat(ApiRequestVisibility.canView(request(), UUID.randomUUID(), Set.of(Permission.QUERY_VIEW_ALL)))
                .isTrue();
    }

    @Test
    void othersCannotView() {
        assertThat(ApiRequestVisibility.canView(request(), UUID.randomUUID(), Set.of())).isFalse();
        assertThat(ApiRequestVisibility.canView(request(), UUID.randomUUID(), null)).isFalse();
    }

    @Test
    void canViewAllRequiresThePermission() {
        assertThat(ApiRequestVisibility.canViewAll(null)).isFalse();
        assertThat(ApiRequestVisibility.canViewAll(Set.of())).isFalse();
        assertThat(ApiRequestVisibility.canViewAll(Set.of(Permission.QUERY_VIEW_ALL))).isTrue();
    }
}
