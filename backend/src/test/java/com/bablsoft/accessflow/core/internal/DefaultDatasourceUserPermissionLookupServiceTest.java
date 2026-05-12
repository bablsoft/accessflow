package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceUserPermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDatasourceUserPermissionLookupServiceTest {

    @Mock DatasourceUserPermissionRepository permissionRepository;
    @InjectMocks DefaultDatasourceUserPermissionLookupService service;

    @Test
    void findForMapsAllFields() {
        var permId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var expiresAt = Instant.parse("2026-12-31T23:59:59Z");

        var entity = newPermission(permId, userId, datasourceId);
        entity.setCanRead(true);
        entity.setCanWrite(true);
        entity.setCanDdl(false);
        entity.setAllowedSchemas(new String[] {"public", "reporting"});
        entity.setAllowedTables(new String[] {"users", "orders"});
        entity.setRestrictedColumns(new String[] {"public.users.ssn", "public.users.email"});
        entity.setExpiresAt(expiresAt);

        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(entity));

        var result = service.findFor(userId, datasourceId);

        assertThat(result).isPresent();
        var view = result.get();
        assertThat(view.id()).isEqualTo(permId);
        assertThat(view.userId()).isEqualTo(userId);
        assertThat(view.datasourceId()).isEqualTo(datasourceId);
        assertThat(view.canRead()).isTrue();
        assertThat(view.canWrite()).isTrue();
        assertThat(view.canDdl()).isFalse();
        assertThat(view.allowedSchemas()).containsExactly("public", "reporting");
        assertThat(view.allowedTables()).containsExactly("users", "orders");
        assertThat(view.restrictedColumns()).containsExactly("public.users.ssn", "public.users.email");
        assertThat(view.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void findForReturnsEmptyAllowedListsWhenArraysAreNull() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var entity = newPermission(UUID.randomUUID(), userId, datasourceId);
        entity.setAllowedSchemas(null);
        entity.setAllowedTables(null);

        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.of(entity));

        var view = service.findFor(userId, datasourceId).orElseThrow();

        assertThat(view.allowedSchemas()).isEmpty();
        assertThat(view.allowedTables()).isEmpty();
        assertThat(view.restrictedColumns()).isEmpty();
        assertThat(view.expiresAt()).isNull();
    }

    @Test
    void findForReturnsEmptyWhenMissing() {
        var userId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        when(permissionRepository.findByUser_IdAndDatasource_Id(userId, datasourceId))
                .thenReturn(Optional.empty());

        assertThat(service.findFor(userId, datasourceId)).isEmpty();
    }

    private static DatasourceUserPermissionEntity newPermission(UUID permId, UUID userId,
                                                                UUID datasourceId) {
        var user = new UserEntity();
        user.setId(userId);
        var datasource = new DatasourceEntity();
        datasource.setId(datasourceId);
        var entity = new DatasourceUserPermissionEntity();
        entity.setId(permId);
        entity.setUser(user);
        entity.setDatasource(datasource);
        return entity;
    }
}
