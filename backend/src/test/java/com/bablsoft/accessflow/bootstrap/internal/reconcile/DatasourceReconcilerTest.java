package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.bootstrap.internal.spec.DatasourceSpec;
import com.bablsoft.accessflow.core.api.CreateDatasourceCommand;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UpdateDatasourceCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatasourceReconcilerTest {

    @Mock DatasourceAdminService datasourceAdminService;
    @InjectMocks DatasourceReconciler reconciler;

    private static final UUID ORG_ID = UUID.randomUUID();

    @Test
    void rejectsCustomDbType() {
        var spec = ds("ds", DbType.CUSTOM, null, null);
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, List.of(spec), Map.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CUSTOM");
    }

    @Test
    void throwsWhenNameMissing() {
        var spec = new DatasourceSpec(null, DbType.POSTGRESQL, "h", 5432, "db", "u", "p",
                SslMode.DISABLE, 10, 100, false, true, null, false, null, null);
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, List.of(spec), Map.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("name");
    }

    @Test
    void throwsWhenDbTypeMissing() {
        var spec = new DatasourceSpec("ds", null, "h", 5432, "db", "u", "p",
                SslMode.DISABLE, 10, 100, false, true, null, false, null, null);
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, List.of(spec), Map.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dbType");
    }

    @Test
    void createsWhenNotFoundResolvingReviewPlanAndAiConfig() {
        var newId = UUID.randomUUID();
        var planId = UUID.randomUUID();
        var aiId = UUID.randomUUID();
        when(datasourceAdminService.listForAdmin(eq(ORG_ID), any(PageRequest.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 500, 0, 0));
        when(datasourceAdminService.create(any(CreateDatasourceCommand.class)))
                .thenAnswer(inv -> view(newId, "prod-pg"));

        var spec = ds("prod-pg", DbType.POSTGRESQL, "standard", "claude");

        reconciler.reconcile(ORG_ID, List.of(spec),
                Map.of("standard", planId),
                Map.of("claude", aiId));

        var captor = ArgumentCaptor.forClass(CreateDatasourceCommand.class);
        verify(datasourceAdminService).create(captor.capture());
        assertThat(captor.getValue().reviewPlanId()).isEqualTo(planId);
        assertThat(captor.getValue().aiConfigId()).isEqualTo(aiId);
        assertThat(captor.getValue().dbType()).isEqualTo(DbType.POSTGRESQL);
    }

    @Test
    void updatesWhenNameMatches() {
        var existingId = UUID.randomUUID();
        when(datasourceAdminService.listForAdmin(eq(ORG_ID), any(PageRequest.class)))
                .thenReturn(new PageResponse<>(List.of(view(existingId, "prod-pg")), 0, 500, 0, 1));
        when(datasourceAdminService.update(eq(existingId), eq(ORG_ID),
                any(UpdateDatasourceCommand.class)))
                .thenReturn(view(existingId, "prod-pg"));

        reconciler.reconcile(ORG_ID, List.of(ds("prod-pg", DbType.POSTGRESQL, null, null)),
                Map.of(), Map.of());

        verify(datasourceAdminService, never()).create(any());
    }

    @Test
    void throwsWhenReviewPlanNameUnresolved() {
        var spec = ds("prod-pg", DbType.POSTGRESQL, "missing", null);
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, List.of(spec), Map.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void throwsWhenAiConfigNameUnresolved() {
        var spec = ds("prod-pg", DbType.POSTGRESQL, null, "missing-ai");
        assertThatThrownBy(() -> reconciler.reconcile(ORG_ID, List.of(spec), Map.of(), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing-ai");
    }

    private DatasourceSpec ds(String name, DbType dbType, String reviewPlan, String aiConfig) {
        return new DatasourceSpec(name, dbType, "host", 5432, "db", "user", "pw",
                SslMode.DISABLE, 10, 100, false, true, reviewPlan, false, aiConfig, null);
    }

    private DatasourceView view(UUID id, String name) {
        return new DatasourceView(id, ORG_ID, name, DbType.POSTGRESQL, "host", 5432, "db", "user",
                SslMode.DISABLE, 10, 100, false, true, null, false, null, null, null, true, Instant.now());
    }
}
