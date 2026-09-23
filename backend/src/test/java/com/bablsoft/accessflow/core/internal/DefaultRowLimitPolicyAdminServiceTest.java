package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CreateRowLimitPolicyCommand;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.IllegalRowLimitPolicyException;
import com.bablsoft.accessflow.core.api.RowLimitPolicyNotFoundException;
import com.bablsoft.accessflow.core.api.UpdateRowLimitPolicyCommand;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.RoleEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.RowLimitPolicyEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RoleRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RowLimitPolicyRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserGroupRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultRowLimitPolicyAdminServiceTest {

    @Mock RowLimitPolicyRepository rowLimitPolicyRepository;
    @Mock RoleRepository roleRepository;
    @Mock DatasourceRepository datasourceRepository;
    @Mock UserRepository userRepository;
    @Mock UserGroupRepository userGroupRepository;
    @Mock MessageSource messageSource;

    private DefaultRowLimitPolicyAdminService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultRowLimitPolicyAdminService(rowLimitPolicyRepository, roleRepository,
                datasourceRepository, userRepository, userGroupRepository, messageSource);
        when(messageSource.getMessage(any(), any(), any())).thenReturn("error");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(datasource(orgId)));
        when(rowLimitPolicyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(roleRepository.findByNameInScope(eq(orgId), any())).thenReturn(Optional.empty());
        var analyst = new RoleEntity();
        analyst.setId(UUID.randomUUID());
        analyst.setName("ANALYST");
        when(roleRepository.findByNameInScope(orgId, "analyst")).thenReturn(Optional.of(analyst));
    }

    @Test
    void listMapsEntitiesToViews() {
        var entity = entity();
        entity.setAppliesToRoles(new String[]{"ANALYST"});
        when(rowLimitPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdOrderByCreatedAtAsc(orgId, datasourceId))
                .thenReturn(List.of(entity));

        var result = service.listForDatasource(datasourceId, orgId);

        assertThat(result).singleElement().satisfies(v -> {
            assertThat(v.schemaName()).isEqualTo("crm");
            assertThat(v.tableName()).isEqualTo("customer");
            assertThat(v.maxRows()).isEqualTo(100);
            assertThat(v.appliesToRoles()).containsExactly("ANALYST");
            assertThat(v.appliesToGroupIds()).isEmpty();
            assertThat(v.appliesToUserIds()).isEmpty();
        });
    }

    @Test
    void listRejectsDatasourceFromOtherOrg() {
        when(datasourceRepository.findById(datasourceId))
                .thenReturn(Optional.of(datasource(UUID.randomUUID())));

        assertThatThrownBy(() -> service.listForDatasource(datasourceId, orgId))
                .isInstanceOf(DatasourceNotFoundException.class);
    }

    @Test
    void listRejectsUnknownDatasource() {
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listForDatasource(datasourceId, orgId))
                .isInstanceOf(DatasourceNotFoundException.class);
    }

    @Test
    void createNormalizesNamesAndPersistsTargets() {
        var userId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        when(userRepository.findAllByOrganization_IdAndIdIn(eq(orgId), anyList()))
                .thenReturn(List.of(new UserEntity()));
        when(userGroupRepository.findAllByOrganization_IdAndIdIn(eq(orgId), anyList()))
                .thenReturn(List.of(new UserGroupEntity()));

        var view = service.create(datasourceId, orgId, new CreateRowLimitPolicyCommand(
                " \"CRM\" ", " `Customer` ", 250, List.of("analyst", " "), List.of(groupId),
                List.of(userId), null));

        var captor = ArgumentCaptor.forClass(RowLimitPolicyEntity.class);
        verify(rowLimitPolicyRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getOrganizationId()).isEqualTo(orgId);
        assertThat(saved.getDatasourceId()).isEqualTo(datasourceId);
        assertThat(saved.getSchemaName()).isEqualTo("crm");
        assertThat(saved.getTableName()).isEqualTo("customer");
        assertThat(saved.getMaxRows()).isEqualTo(250);
        assertThat(saved.getAppliesToRoles()).containsExactly("ANALYST");
        assertThat(saved.getAppliesToGroupIds()).containsExactly(groupId);
        assertThat(saved.getAppliesToUserIds()).containsExactly(userId);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(view.id()).isEqualTo(saved.getId());
    }

    @Test
    void createStoresBlankSchemaAndEmptyTargetsAsNull() {
        service.create(datasourceId, orgId, new CreateRowLimitPolicyCommand(
                "  ", "orders", 5, null, List.of(), null, false));

        var captor = ArgumentCaptor.forClass(RowLimitPolicyEntity.class);
        verify(rowLimitPolicyRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getSchemaName()).isNull();
        assertThat(saved.getAppliesToRoles()).isNull();
        assertThat(saved.getAppliesToGroupIds()).isNull();
        assertThat(saved.getAppliesToUserIds()).isNull();
        assertThat(saved.isEnabled()).isFalse();
    }

    @Test
    void createRejectsBlankTable() {
        assertThatThrownBy(() -> service.create(datasourceId, orgId,
                new CreateRowLimitPolicyCommand(null, " \"\" ", 5, null, null, null, null)))
                .isInstanceOf(IllegalRowLimitPolicyException.class);
        assertThatThrownBy(() -> service.create(datasourceId, orgId,
                new CreateRowLimitPolicyCommand(null, null, 5, null, null, null, null)))
                .isInstanceOf(IllegalRowLimitPolicyException.class);
        verify(rowLimitPolicyRepository, never()).save(any());
    }

    @Test
    void createRejectsMissingOrNonPositiveMaxRows() {
        assertThatThrownBy(() -> service.create(datasourceId, orgId,
                new CreateRowLimitPolicyCommand(null, "orders", null, null, null, null, null)))
                .isInstanceOf(IllegalRowLimitPolicyException.class);
        assertThatThrownBy(() -> service.create(datasourceId, orgId,
                new CreateRowLimitPolicyCommand(null, "orders", 0, null, null, null, null)))
                .isInstanceOf(IllegalRowLimitPolicyException.class);
    }

    @Test
    void createRejectsUnknownRole() {
        assertThatThrownBy(() -> service.create(datasourceId, orgId,
                new CreateRowLimitPolicyCommand(null, "orders", 5, List.of("ghost"), null, null,
                        null)))
                .isInstanceOf(IllegalRowLimitPolicyException.class);
    }

    @Test
    void createRejectsUsersOrGroupsOutsideTheOrganization() {
        when(userRepository.findAllByOrganization_IdAndIdIn(eq(orgId), anyList()))
                .thenReturn(List.of());
        when(userGroupRepository.findAllByOrganization_IdAndIdIn(eq(orgId), anyList()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.create(datasourceId, orgId,
                new CreateRowLimitPolicyCommand(null, "orders", 5, null, null,
                        List.of(UUID.randomUUID()), null)))
                .isInstanceOf(IllegalRowLimitPolicyException.class);
        assertThatThrownBy(() -> service.create(datasourceId, orgId,
                new CreateRowLimitPolicyCommand(null, "orders", 5, null,
                        List.of(UUID.randomUUID()), null, null)))
                .isInstanceOf(IllegalRowLimitPolicyException.class);
    }

    @Test
    void updateOverwritesFields() {
        var entity = entity();
        when(rowLimitPolicyRepository.findByIdAndOrganizationId(entity.getId(), orgId))
                .thenReturn(Optional.of(entity));

        var view = service.update(entity.getId(), datasourceId, orgId,
                new UpdateRowLimitPolicyCommand(null, "Orders", 42, null, null, null, true));

        assertThat(view.schemaName()).isNull();
        assertThat(view.tableName()).isEqualTo("orders");
        assertThat(view.maxRows()).isEqualTo(42);
    }

    @Test
    void updateRejectsPolicyOnAnotherDatasource() {
        var entity = entity();
        entity.setDatasourceId(UUID.randomUUID());
        when(rowLimitPolicyRepository.findByIdAndOrganizationId(entity.getId(), orgId))
                .thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.update(entity.getId(), datasourceId, orgId,
                new UpdateRowLimitPolicyCommand(null, "orders", 5, null, null, null, null)))
                .isInstanceOf(RowLimitPolicyNotFoundException.class);
    }

    @Test
    void deleteRemovesPolicyInScope() {
        var entity = entity();
        when(rowLimitPolicyRepository.findByIdAndOrganizationId(entity.getId(), orgId))
                .thenReturn(Optional.of(entity));

        service.delete(entity.getId(), datasourceId, orgId);

        verify(rowLimitPolicyRepository).delete(entity);
    }

    @Test
    void deleteUnknownPolicyThrowsNotFound() {
        var id = UUID.randomUUID();
        when(rowLimitPolicyRepository.findByIdAndOrganizationId(id, orgId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id, datasourceId, orgId))
                .isInstanceOf(RowLimitPolicyNotFoundException.class);
    }

    @Test
    void normalizeIdentifierStripsQuotesAndLowercases() {
        assertThat(DefaultRowLimitPolicyAdminService.normalizeIdentifier(" [Sales].\"Orders\" "))
                .isEqualTo("sales.orders");
        assertThat(DefaultRowLimitPolicyAdminService.normalizeIdentifier(null)).isEmpty();
    }

    private DatasourceEntity datasource(UUID ownerOrgId) {
        var org = new OrganizationEntity();
        org.setId(ownerOrgId);
        var ds = new DatasourceEntity();
        ds.setId(datasourceId);
        ds.setOrganization(org);
        return ds;
    }

    private RowLimitPolicyEntity entity() {
        var entity = new RowLimitPolicyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setDatasourceId(datasourceId);
        entity.setSchemaName("crm");
        entity.setTableName("customer");
        entity.setMaxRows(100);
        entity.setEnabled(true);
        return entity;
    }
}
