package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CreateMaskingPolicyCommand;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.IllegalMaskingPolicyException;
import com.bablsoft.accessflow.core.api.MaskingPolicyNotFoundException;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.UpdateMaskingPolicyCommand;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.MaskingPolicyEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.RoleEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.MaskingPolicyRepository;
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
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
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
class DefaultMaskingPolicyAdminServiceTest {

    @Mock MaskingPolicyRepository maskingPolicyRepository;
    @Mock RoleRepository roleRepository;
    @Mock DatasourceRepository datasourceRepository;
    @Mock UserRepository userRepository;
    @Mock UserGroupRepository userGroupRepository;
    @Mock MessageSource messageSource;

    private DefaultMaskingPolicyAdminService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultMaskingPolicyAdminService(maskingPolicyRepository, roleRepository,
                datasourceRepository, userRepository, userGroupRepository, new ObjectMapper(),
                messageSource);
        when(messageSource.getMessage(any(), any(), any())).thenReturn("error");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(datasource(orgId)));
        when(maskingPolicyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(roleRepository.findByNameInScope(eq(orgId), any())).thenReturn(Optional.empty());
        when(roleRepository.findByNameInScope(orgId, "admin")).thenReturn(Optional.of(role("ADMIN")));
        when(roleRepository.findByNameInScope(orgId, "ADMIN")).thenReturn(Optional.of(role("ADMIN")));
    }

    private RoleEntity role(String name) {
        var role = new RoleEntity();
        role.setId(UUID.randomUUID());
        role.setName(name);
        role.setSystem(true);
        return role;
    }

    @Test
    void listMapsEntitiesToViews() {
        var entity = entity(MaskingStrategy.PARTIAL, "{\"visible_suffix\":\"4\"}");
        entity.setRevealToRoles(new String[]{"ADMIN"});
        when(maskingPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdOrderByCreatedAtAsc(orgId, datasourceId))
                .thenReturn(List.of(entity));

        var result = service.listForDatasource(datasourceId, orgId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().strategy()).isEqualTo(MaskingStrategy.PARTIAL);
        assertThat(result.getFirst().strategyParams()).containsEntry("visible_suffix", "4");
        assertThat(result.getFirst().revealToRoles()).containsExactly("ADMIN");
    }

    @Test
    void listRejectsDatasourceFromOtherOrg() {
        when(datasourceRepository.findById(datasourceId))
                .thenReturn(Optional.of(datasource(UUID.randomUUID())));

        assertThatThrownBy(() -> service.listForDatasource(datasourceId, orgId))
                .isInstanceOf(DatasourceNotFoundException.class);
    }

    @Test
    void listRejectsMissingDatasource() {
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listForDatasource(datasourceId, orgId))
                .isInstanceOf(DatasourceNotFoundException.class);
    }

    @Test
    void createPersistsNormalizedPolicy() {
        var userId = UUID.randomUUID();
        when(userRepository.findAllByOrganization_IdAndIdIn(eq(orgId), anyList()))
                .thenReturn(List.of(new UserEntity()));
        var command = new CreateMaskingPolicyCommand("public.users.email", MaskingStrategy.PARTIAL,
                Map.of("visible_suffix", "4"), List.of("admin"), List.of(), List.of(userId), null);

        var view = service.create(datasourceId, orgId, command);

        var captor = ArgumentCaptor.forClass(MaskingPolicyEntity.class);
        verify(maskingPolicyRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getColumnRef()).isEqualTo("public.users.email");
        assertThat(saved.getStrategyParams()).contains("visible_suffix").contains("4");
        assertThat(saved.getRevealToRoles()).containsExactly("ADMIN");
        assertThat(saved.getRevealToUserIds()).containsExactly(userId);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(view.strategy()).isEqualTo(MaskingStrategy.PARTIAL);
    }

    @Test
    void createRejectsBlankColumnRef() {
        var command = new CreateMaskingPolicyCommand("  ", MaskingStrategy.FULL, Map.of(),
                List.of(), List.of(), List.of(), true);

        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalMaskingPolicyException.class);
        verify(maskingPolicyRepository, never()).save(any());
    }

    @Test
    void createRejectsNullStrategy() {
        var command = new CreateMaskingPolicyCommand("col", null, Map.of(),
                List.of(), List.of(), List.of(), true);

        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalMaskingPolicyException.class);
    }

    @Test
    void createRejectsInvalidVisibleSuffix() {
        var nonNumeric = new CreateMaskingPolicyCommand("col", MaskingStrategy.PARTIAL,
                Map.of("visible_suffix", "abc"), List.of(), List.of(), List.of(), true);
        assertThatThrownBy(() -> service.create(datasourceId, orgId, nonNumeric))
                .isInstanceOf(IllegalMaskingPolicyException.class);

        var outOfRange = new CreateMaskingPolicyCommand("col", MaskingStrategy.PARTIAL,
                Map.of("visible_suffix", "0"), List.of(), List.of(), List.of(), true);
        assertThatThrownBy(() -> service.create(datasourceId, orgId, outOfRange))
                .isInstanceOf(IllegalMaskingPolicyException.class);
    }

    @Test
    void createRejectsUnknownRevealRole() {
        var command = new CreateMaskingPolicyCommand("col", MaskingStrategy.FULL, Map.of(),
                List.of("WIZARD"), List.of(), List.of(), true);

        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalMaskingPolicyException.class);
    }

    @Test
    void createRejectsRevealUserNotInOrganization() {
        when(userRepository.findAllByOrganization_IdAndIdIn(eq(orgId), anyList()))
                .thenReturn(List.of());
        var command = new CreateMaskingPolicyCommand("col", MaskingStrategy.FULL, Map.of(),
                List.of(), List.of(), List.of(UUID.randomUUID()), true);

        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalMaskingPolicyException.class);
    }

    @Test
    void createRejectsRevealGroupNotInOrganization() {
        when(userGroupRepository.findAllByOrganization_IdAndIdIn(eq(orgId), anyList()))
                .thenReturn(List.of());
        var command = new CreateMaskingPolicyCommand("col", MaskingStrategy.FULL, Map.of(),
                List.of(), List.of(UUID.randomUUID()), List.of(), true);

        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalMaskingPolicyException.class);
    }

    @Test
    void createAcceptsRevealGroupInOrganization() {
        var groupId = UUID.randomUUID();
        when(userGroupRepository.findAllByOrganization_IdAndIdIn(eq(orgId), anyList()))
                .thenReturn(List.of(new UserGroupEntity()));
        var command = new CreateMaskingPolicyCommand("col", MaskingStrategy.FULL, Map.of(),
                List.of(), List.of(groupId), List.of(), false);

        var view = service.create(datasourceId, orgId, command);

        assertThat(view.enabled()).isFalse();
    }

    @Test
    void updateAppliesChanges() {
        var entity = entity(MaskingStrategy.FULL, "{}");
        when(maskingPolicyRepository.findByIdAndOrganizationId(entity.getId(), orgId))
                .thenReturn(Optional.of(entity));
        var command = new UpdateMaskingPolicyCommand("public.users.ssn", MaskingStrategy.HASH,
                Map.of(), List.of(), List.of(), List.of(), false);

        var view = service.update(entity.getId(), datasourceId, orgId, command);

        assertThat(view.columnRef()).isEqualTo("public.users.ssn");
        assertThat(view.strategy()).isEqualTo(MaskingStrategy.HASH);
        assertThat(view.enabled()).isFalse();
    }

    @Test
    void updateRejectsPolicyFromDifferentDatasource() {
        var entity = entity(MaskingStrategy.FULL, "{}");
        entity.setDatasourceId(UUID.randomUUID());
        when(maskingPolicyRepository.findByIdAndOrganizationId(entity.getId(), orgId))
                .thenReturn(Optional.of(entity));
        var command = new UpdateMaskingPolicyCommand("col", MaskingStrategy.FULL, Map.of(),
                List.of(), List.of(), List.of(), true);

        assertThatThrownBy(() -> service.update(entity.getId(), datasourceId, orgId, command))
                .isInstanceOf(MaskingPolicyNotFoundException.class);
    }

    @Test
    void updateRejectsMissingPolicy() {
        var id = UUID.randomUUID();
        when(maskingPolicyRepository.findByIdAndOrganizationId(id, orgId))
                .thenReturn(Optional.empty());
        var command = new UpdateMaskingPolicyCommand("col", MaskingStrategy.FULL, Map.of(),
                List.of(), List.of(), List.of(), true);

        assertThatThrownBy(() -> service.update(id, datasourceId, orgId, command))
                .isInstanceOf(MaskingPolicyNotFoundException.class);
    }

    @Test
    void deleteRemovesPolicy() {
        var entity = entity(MaskingStrategy.FULL, "{}");
        when(maskingPolicyRepository.findByIdAndOrganizationId(entity.getId(), orgId))
                .thenReturn(Optional.of(entity));

        service.delete(entity.getId(), datasourceId, orgId);

        verify(maskingPolicyRepository).delete(entity);
    }

    @Test
    void deleteRejectsMissingPolicy() {
        var id = UUID.randomUUID();
        when(maskingPolicyRepository.findByIdAndOrganizationId(id, orgId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id, datasourceId, orgId))
                .isInstanceOf(MaskingPolicyNotFoundException.class);
        verify(maskingPolicyRepository, never()).delete(any());
    }

    private DatasourceEntity datasource(UUID ownerOrgId) {
        var org = new OrganizationEntity();
        org.setId(ownerOrgId);
        var ds = new DatasourceEntity();
        ds.setId(datasourceId);
        ds.setOrganization(org);
        return ds;
    }

    private MaskingPolicyEntity entity(MaskingStrategy strategy, String params) {
        var entity = new MaskingPolicyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setDatasourceId(datasourceId);
        entity.setColumnRef("col");
        entity.setStrategy(strategy);
        entity.setStrategyParams(params);
        entity.setEnabled(true);
        return entity;
    }
}
