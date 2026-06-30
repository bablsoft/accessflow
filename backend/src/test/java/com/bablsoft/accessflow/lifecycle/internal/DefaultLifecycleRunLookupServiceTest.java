package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.lifecycle.api.LifecycleAction;
import com.bablsoft.accessflow.lifecycle.api.LifecycleRunKind;
import com.bablsoft.accessflow.lifecycle.api.LifecycleRunStatus;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.LifecycleRunEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.LifecycleRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultLifecycleRunLookupServiceTest {

    @Mock
    private LifecycleRunRepository repository;
    @InjectMocks
    private DefaultLifecycleRunLookupService service;

    @Test
    void mapsEntitiesAndAppliesLimit() {
        var org = UUID.randomUUID();
        var ds = UUID.randomUUID();
        var from = Instant.parse("2026-06-01T00:00:00Z");
        var to = Instant.parse("2026-07-01T00:00:00Z");
        var entity = new LifecycleRunEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(org);
        entity.setDatasourceId(ds);
        entity.setKind(LifecycleRunKind.ERASURE_REQUEST);
        entity.setStatus(LifecycleRunStatus.COMPLETED);
        entity.setAction(LifecycleAction.HARD_DELETE);
        entity.setAffectedRows(7);
        entity.setMethod("HARD_DELETE");
        when(repository.findForPeriod(eq(org), eq(from), eq(to), eq(ds), any()))
                .thenReturn(List.of(entity));

        var views = service.findForPeriod(org, from, to, ds, 50);

        assertThat(views).hasSize(1);
        assertThat(views.getFirst().affectedRows()).isEqualTo(7);
        assertThat(views.getFirst().kind()).isEqualTo(LifecycleRunKind.ERASURE_REQUEST);

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findForPeriod(eq(org), eq(from), eq(to), eq(ds), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(50);
    }
}
