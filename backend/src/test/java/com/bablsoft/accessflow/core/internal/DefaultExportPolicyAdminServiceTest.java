package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CreateExportPolicyCommand;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.ExportPolicyMode;
import com.bablsoft.accessflow.core.api.ExportPolicyNotFoundException;
import com.bablsoft.accessflow.core.api.IllegalExportPolicyException;
import com.bablsoft.accessflow.core.api.UpdateExportPolicyCommand;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.ExportPolicyEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.RoleEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.ExportPolicyRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RoleRepository;
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

import java.util.Arrays;
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
class DefaultExportPolicyAdminServiceTest {

    @Mock ExportPolicyRepository exportPolicyRepository;
    @Mock RoleRepository roleRepository;
    @Mock DatasourceRepository datasourceRepository;
    @Mock UserRepository userRepository;
    @Mock UserGroupRepository userGroupRepository;
    @Mock MessageSource messageSource;

    private DefaultExportPolicyAdminService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultExportPolicyAdminService(exportPolicyRepository, roleRepository,
                datasourceRepository, userRepository, userGroupRepository, messageSource);
        when(messageSource.getMessage(any(), any(), any())).thenReturn("error");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(datasource(orgId)));
        when(exportPolicyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(roleRepository.findByNameInScope(eq(orgId), any())).thenReturn(Optional.empty());
        when(roleRepository.findByNameInScope(orgId, "analyst"))
                .thenReturn(Optional.of(role("ANALYST")));
        when(roleRepository.findByNameInScope(orgId, "ANALYST"))
                .thenReturn(Optional.of(role("ANALYST")));
    }

    @Test
    void listMapsEntitiesToViews() {
        var entity = entity();
        entity.setAppliesToRoles(new String[]{"ANALYST"});
        when(exportPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdOrderByCreatedAtAsc(orgId, datasourceId))
                .thenReturn(List.of(entity));

        var result = service.listForDatasource(datasourceId, orgId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().id()).isEqualTo(entity.getId());
        assertThat(result.getFirst().datasourceId()).isEqualTo(datasourceId);
        assertThat(result.getFirst().mode()).isEqualTo(ExportPolicyMode.DENY_CLASSIFIED);
        assertThat(result.getFirst().denyClassifications())
                .containsExactly(DataClassification.PII, DataClassification.PCI);
        assertThat(result.getFirst().appliesToRoles()).containsExactly("ANALYST");
        assertThat(result.getFirst().appliesToGroupIds()).isEmpty();
        assertThat(result.getFirst().appliesToUserIds()).isEmpty();
        assertThat(result.getFirst().enabled()).isTrue();
    }

    @Test
    void listRejectsMissingDatasource() {
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listForDatasource(datasourceId, orgId))
                .isInstanceOf(DatasourceNotFoundException.class);
    }

    @Test
    void listRejectsDatasourceFromOtherOrg() {
        when(datasourceRepository.findById(datasourceId))
                .thenReturn(Optional.of(datasource(UUID.randomUUID())));

        assertThatThrownBy(() -> service.listForDatasource(datasourceId, orgId))
                .isInstanceOf(DatasourceNotFoundException.class);
    }

    @Test
    void createPersistsRowCapPolicyWithCanonicalRole() {
        var userId = UUID.randomUUID();
        when(userRepository.findAllByOrganization_IdAndIdIn(eq(orgId), anyList()))
                .thenReturn(List.of(new UserEntity()));
        var command = new CreateExportPolicyCommand(ExportPolicyMode.ROW_CAP, 500, null,
                List.of("analyst"), List.of(), List.of(userId), null);

        var view = service.create(datasourceId, orgId, command);

        var captor = ArgumentCaptor.forClass(ExportPolicyEntity.class);
        verify(exportPolicyRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getOrganizationId()).isEqualTo(orgId);
        assertThat(saved.getDatasourceId()).isEqualTo(datasourceId);
        assertThat(saved.getMode()).isEqualTo(ExportPolicyMode.ROW_CAP);
        assertThat(saved.getRowCap()).isEqualTo(500);
        assertThat(saved.getDenyClassifications()).isNull();
        assertThat(saved.getAppliesToRoles()).containsExactly("ANALYST");
        assertThat(saved.getAppliesToGroupIds()).isNull();
        assertThat(saved.getAppliesToUserIds()).containsExactly(userId);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(view.mode()).isEqualTo(ExportPolicyMode.ROW_CAP);
        assertThat(view.rowCap()).isEqualTo(500);
        assertThat(view.appliesToRoles()).containsExactly("ANALYST");
        assertThat(view.appliesToUserIds()).containsExactly(userId);
        assertThat(view.enabled()).isTrue();
    }

    @Test
    void createRejectsNullMode() {
        var command = new CreateExportPolicyCommand(null, null, List.of(), List.of(), List.of(),
                List.of(), true);

        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalExportPolicyException.class);
        verify(exportPolicyRepository, never()).save(any());
    }

    @Test
    void createRejectsRowCapModeWithoutRowCap() {
        var command = create(ExportPolicyMode.ROW_CAP, null);

        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalExportPolicyException.class);
    }

    @Test
    void createRejectsRowCapBelowOne() {
        var command = create(ExportPolicyMode.ROW_CAP, 0);

        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalExportPolicyException.class);
    }

    @Test
    void createRejectsRowCapAboveMax() {
        var command = create(ExportPolicyMode.ROW_CAP, 1_000_001);

        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalExportPolicyException.class);
    }

    @Test
    void createAcceptsRowCapAtBounds() {
        assertThat(service.create(datasourceId, orgId, create(ExportPolicyMode.ROW_CAP, 1))
                .rowCap()).isEqualTo(1);
        assertThat(service.create(datasourceId, orgId,
                create(ExportPolicyMode.ROW_CAP, DefaultExportPolicyAdminService.MAX_ROW_CAP))
                .rowCap()).isEqualTo(DefaultExportPolicyAdminService.MAX_ROW_CAP);
    }

    @Test
    void createRejectsRowCapOnNonRowCapMode() {
        var command = create(ExportPolicyMode.ALLOW, 5);

        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalExportPolicyException.class);
    }

    @Test
    void createRejectsDenyClassificationsOnNonDenyClassifiedMode() {
        var command = new CreateExportPolicyCommand(ExportPolicyMode.WATERMARK, null,
                List.of(DataClassification.PII), List.of(), List.of(), List.of(), true);

        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalExportPolicyException.class);
    }

    @Test
    void createDedupesDenyClassifications() {
        var command = new CreateExportPolicyCommand(ExportPolicyMode.DENY_CLASSIFIED, null,
                List.of(DataClassification.PII, DataClassification.PII, DataClassification.PCI),
                List.of(), List.of(), List.of(), true);

        var view = service.create(datasourceId, orgId, command);

        var captor = ArgumentCaptor.forClass(ExportPolicyEntity.class);
        verify(exportPolicyRepository).save(captor.capture());
        assertThat(captor.getValue().getDenyClassifications()).containsExactly("PII", "PCI");
        assertThat(view.denyClassifications())
                .containsExactly(DataClassification.PII, DataClassification.PCI);
    }

    @Test
    void createSkipsBlankAndNullRoleEntries() {
        var command = new CreateExportPolicyCommand(ExportPolicyMode.ALLOW, null, List.of(),
                Arrays.asList(null, "  ", "analyst"), List.of(), List.of(), true);

        var view = service.create(datasourceId, orgId, command);

        assertThat(view.appliesToRoles()).containsExactly("ANALYST");
    }

    @Test
    void createRejectsUnknownRole() {
        var command = new CreateExportPolicyCommand(ExportPolicyMode.ALLOW, null, List.of(),
                List.of("WIZARD"), List.of(), List.of(), true);

        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalExportPolicyException.class);
    }

    @Test
    void createRejectsAppliesUserNotInOrganization() {
        when(userRepository.findAllByOrganization_IdAndIdIn(eq(orgId), anyList()))
                .thenReturn(List.of());
        var command = new CreateExportPolicyCommand(ExportPolicyMode.ALLOW, null, List.of(),
                List.of(), List.of(), List.of(UUID.randomUUID()), true);

        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalExportPolicyException.class);
    }

    @Test
    void createAcceptsDuplicateAppliesUserIds() {
        var userId = UUID.randomUUID();
        when(userRepository.findAllByOrganization_IdAndIdIn(eq(orgId), anyList()))
                .thenReturn(List.of(new UserEntity()));
        var command = new CreateExportPolicyCommand(ExportPolicyMode.ALLOW, null, List.of(),
                List.of(), List.of(), List.of(userId, userId), true);

        var view = service.create(datasourceId, orgId, command);

        assertThat(view.appliesToUserIds()).containsExactly(userId, userId);
    }

    @Test
    void createRejectsAppliesGroupNotInOrganization() {
        when(userGroupRepository.findAllByOrganization_IdAndIdIn(eq(orgId), anyList()))
                .thenReturn(List.of());
        var command = new CreateExportPolicyCommand(ExportPolicyMode.ALLOW, null, List.of(),
                List.of(), List.of(UUID.randomUUID()), List.of(), true);

        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalExportPolicyException.class);
    }

    @Test
    void createAcceptsAppliesGroupInOrganizationAndEnabledFalse() {
        when(userGroupRepository.findAllByOrganization_IdAndIdIn(eq(orgId), anyList()))
                .thenReturn(List.of(new UserGroupEntity()));
        var command = new CreateExportPolicyCommand(ExportPolicyMode.ALLOW, null, List.of(),
                List.of(), List.of(UUID.randomUUID()), List.of(), false);

        var view = service.create(datasourceId, orgId, command);

        assertThat(view.enabled()).isFalse();
    }

    @Test
    void updateAppliesChanges() {
        var entity = entity();
        when(exportPolicyRepository.findByIdAndOrganizationId(entity.getId(), orgId))
                .thenReturn(Optional.of(entity));
        var command = new UpdateExportPolicyCommand(ExportPolicyMode.WATERMARK, null, null,
                List.of(), List.of(), List.of(), false);

        var view = service.update(entity.getId(), datasourceId, orgId, command);

        assertThat(view.mode()).isEqualTo(ExportPolicyMode.WATERMARK);
        assertThat(view.rowCap()).isNull();
        assertThat(view.denyClassifications()).isEmpty();
        assertThat(view.enabled()).isFalse();
    }

    @Test
    void updateValidatesRowCapLikeCreate() {
        var entity = entity();
        when(exportPolicyRepository.findByIdAndOrganizationId(entity.getId(), orgId))
                .thenReturn(Optional.of(entity));
        var command = new UpdateExportPolicyCommand(ExportPolicyMode.ROW_CAP, null, null,
                List.of(), List.of(), List.of(), true);

        assertThatThrownBy(() -> service.update(entity.getId(), datasourceId, orgId, command))
                .isInstanceOf(IllegalExportPolicyException.class);
        verify(exportPolicyRepository, never()).save(any());
    }

    @Test
    void updateRejectsPolicyFromDifferentDatasource() {
        var entity = entity();
        entity.setDatasourceId(UUID.randomUUID());
        when(exportPolicyRepository.findByIdAndOrganizationId(entity.getId(), orgId))
                .thenReturn(Optional.of(entity));
        var command = new UpdateExportPolicyCommand(ExportPolicyMode.ALLOW, null, null,
                List.of(), List.of(), List.of(), true);

        assertThatThrownBy(() -> service.update(entity.getId(), datasourceId, orgId, command))
                .isInstanceOf(ExportPolicyNotFoundException.class);
    }

    @Test
    void updateRejectsMissingPolicy() {
        var id = UUID.randomUUID();
        when(exportPolicyRepository.findByIdAndOrganizationId(id, orgId))
                .thenReturn(Optional.empty());
        var command = new UpdateExportPolicyCommand(ExportPolicyMode.ALLOW, null, null,
                List.of(), List.of(), List.of(), true);

        assertThatThrownBy(() -> service.update(id, datasourceId, orgId, command))
                .isInstanceOf(ExportPolicyNotFoundException.class);
    }

    @Test
    void deleteRemovesPolicy() {
        var entity = entity();
        when(exportPolicyRepository.findByIdAndOrganizationId(entity.getId(), orgId))
                .thenReturn(Optional.of(entity));

        service.delete(entity.getId(), datasourceId, orgId);

        verify(exportPolicyRepository).delete(entity);
    }

    @Test
    void deleteRejectsMissingPolicy() {
        var id = UUID.randomUUID();
        when(exportPolicyRepository.findByIdAndOrganizationId(id, orgId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id, datasourceId, orgId))
                .isInstanceOf(ExportPolicyNotFoundException.class);
        verify(exportPolicyRepository, never()).delete(any());
    }

    private CreateExportPolicyCommand create(ExportPolicyMode mode, Integer rowCap) {
        return new CreateExportPolicyCommand(mode, rowCap, null, List.of(), List.of(), List.of(),
                true);
    }

    private RoleEntity role(String name) {
        var role = new RoleEntity();
        role.setId(UUID.randomUUID());
        role.setName(name);
        role.setSystem(true);
        return role;
    }

    private DatasourceEntity datasource(UUID ownerOrgId) {
        var org = new OrganizationEntity();
        org.setId(ownerOrgId);
        var ds = new DatasourceEntity();
        ds.setId(datasourceId);
        ds.setOrganization(org);
        return ds;
    }

    private ExportPolicyEntity entity() {
        var entity = new ExportPolicyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setDatasourceId(datasourceId);
        entity.setMode(ExportPolicyMode.DENY_CLASSIFIED);
        entity.setDenyClassifications(new String[]{"PII", "PCI"});
        entity.setEnabled(true);
        return entity;
    }
}
