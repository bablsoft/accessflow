package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DataBudgetBreachAction;
import com.bablsoft.accessflow.core.api.DataBudgetCommand;
import com.bablsoft.accessflow.core.api.DataBudgetNotFoundException;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.IllegalDataBudgetException;
import com.bablsoft.accessflow.core.internal.persistence.entity.DataBudgetEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.RoleEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DataBudgetRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
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
class DefaultDataBudgetAdminServiceTest {

    @Mock DataBudgetRepository dataBudgetRepository;
    @Mock RoleRepository roleRepository;
    @Mock DatasourceRepository datasourceRepository;
    @Mock UserRepository userRepository;
    @Mock UserGroupRepository userGroupRepository;
    @Mock MessageSource messageSource;

    private DefaultDataBudgetAdminService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultDataBudgetAdminService(dataBudgetRepository, roleRepository,
                datasourceRepository, userRepository, userGroupRepository, messageSource);
        when(messageSource.getMessage(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(datasource(orgId)));
        when(dataBudgetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
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
        entity.setWarnThresholdPercent((short) 80);
        when(dataBudgetRepository
                .findAllByOrganizationIdAndDatasourceIdOrderByCreatedAtAsc(orgId, datasourceId))
                .thenReturn(List.of(entity));

        var result = service.listForDatasource(datasourceId, orgId);

        assertThat(result).singleElement().satisfies(v -> {
            assertThat(v.name()).isEqualTo("Analysts daily");
            assertThat(v.maxRows()).isEqualTo(100_000L);
            assertThat(v.maxBytes()).isNull();
            assertThat(v.windowMinutes()).isEqualTo(1440);
            assertThat(v.breachAction()).isEqualTo(DataBudgetBreachAction.REQUIRE_REVIEW);
            assertThat(v.warnThresholdPercent()).isEqualTo(80);
            assertThat(v.appliesToRoles()).containsExactly("ANALYST");
            assertThat(v.appliesToGroupIds()).isEmpty();
            assertThat(v.appliesToUserIds()).isEmpty();
        });
    }

    @Test
    void listRejectsDatasourceOfAnotherOrganization() {
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
    void createAppliesDefaultsAndNormalizesTargets() {
        var userId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        when(userRepository.findAllByOrganization_IdAndIdIn(eq(orgId), anyList()))
                .thenReturn(List.of(new UserEntity()));
        when(userGroupRepository.findAllByOrganization_IdAndIdIn(eq(orgId), anyList()))
                .thenReturn(List.of(new UserGroupEntity()));

        var view = service.create(datasourceId, orgId, new DataBudgetCommand("  Daily cap  ",
                null, 5_000_000_000L, null, null, null, List.of("analyst", " "),
                List.of(groupId, groupId), List.of(userId), null));

        var captor = ArgumentCaptor.forClass(DataBudgetEntity.class);
        verify(dataBudgetRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getOrganizationId()).isEqualTo(orgId);
        assertThat(saved.getDatasourceId()).isEqualTo(datasourceId);
        assertThat(saved.getName()).isEqualTo("Daily cap");
        assertThat(saved.getMaxRows()).isNull();
        assertThat(saved.getMaxBytes()).isEqualTo(5_000_000_000L);
        assertThat(saved.getWindowMinutes()).isEqualTo(1440);
        assertThat(saved.getBreachAction()).isEqualTo(DataBudgetBreachAction.REQUIRE_REVIEW);
        assertThat(saved.getWarnThresholdPercent()).isNull();
        assertThat(saved.getAppliesToRoles()).containsExactly("ANALYST");
        assertThat(saved.getAppliesToGroupIds()).containsExactly(groupId);
        assertThat(saved.getAppliesToUserIds()).containsExactly(userId);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(view.id()).isEqualTo(saved.getId());
    }

    @Test
    void createKeepsExplicitValues() {
        service.create(datasourceId, orgId, new DataBudgetCommand("Strict", 1_000L, null, 60,
                DataBudgetBreachAction.REJECT, 75, null, null, null, false));

        var captor = ArgumentCaptor.forClass(DataBudgetEntity.class);
        verify(dataBudgetRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getWindowMinutes()).isEqualTo(60);
        assertThat(saved.getBreachAction()).isEqualTo(DataBudgetBreachAction.REJECT);
        assertThat(saved.getWarnThresholdPercent()).isEqualTo((short) 75);
        assertThat(saved.getAppliesToRoles()).isNull();
        assertThat(saved.isEnabled()).isFalse();
    }

    @Test
    void createRejectsInvalidInput() {
        assertInvalid(new DataBudgetCommand(" ", 1L, null, null, null, null, null, null, null, null),
                "error.data_budget.name_invalid");
        assertInvalid(new DataBudgetCommand(null, 1L, null, null, null, null, null, null, null, null),
                "error.data_budget.name_invalid");
        assertInvalid(new DataBudgetCommand("x".repeat(121), 1L, null, null, null, null, null, null,
                null, null), "error.data_budget.name_invalid");
        assertInvalid(new DataBudgetCommand("n", null, null, null, null, null, null, null, null,
                null), "error.data_budget.limit_required");
        assertInvalid(new DataBudgetCommand("n", 0L, null, null, null, null, null, null, null, null),
                "error.data_budget.limit_invalid");
        assertInvalid(new DataBudgetCommand("n", null, -1L, null, null, null, null, null, null,
                null), "error.data_budget.limit_invalid");
        assertInvalid(new DataBudgetCommand("n", 1L, null, 59, null, null, null, null, null, null),
                "error.data_budget.window_invalid");
        assertInvalid(new DataBudgetCommand("n", 1L, null, 44_641, null, null, null, null, null,
                null), "error.data_budget.window_invalid");
        assertInvalid(new DataBudgetCommand("n", 1L, null, null, null, 0, null, null, null, null),
                "error.data_budget.threshold_invalid");
        assertInvalid(new DataBudgetCommand("n", 1L, null, null, null, 100, null, null, null, null),
                "error.data_budget.threshold_invalid");
        assertInvalid(new DataBudgetCommand("n", 1L, null, null, null, null, List.of("ghost"), null,
                null, null), "error.data_budget.unknown_role");
        verify(dataBudgetRepository, never()).save(any());
    }

    @Test
    void createRejectsTargetsOutsideOrganization() {
        when(userRepository.findAllByOrganization_IdAndIdIn(eq(orgId), anyList()))
                .thenReturn(List.of());
        when(userGroupRepository.findAllByOrganization_IdAndIdIn(eq(orgId), anyList()))
                .thenReturn(List.of());

        assertInvalid(new DataBudgetCommand("n", 1L, null, null, null, null, null, null,
                List.of(UUID.randomUUID()), null), "error.data_budget.user_not_in_org");
        assertInvalid(new DataBudgetCommand("n", 1L, null, null, null, null, null,
                List.of(UUID.randomUUID()), null, null), "error.data_budget.group_not_in_org");
    }

    @Test
    void updateReappliesEveryField() {
        var entity = entity();
        when(dataBudgetRepository.findByIdAndOrganizationId(entity.getId(), orgId))
                .thenReturn(Optional.of(entity));

        var view = service.update(entity.getId(), datasourceId, orgId, new DataBudgetCommand(
                "Renamed", null, 1_024L, 120, DataBudgetBreachAction.REJECT, 50, null, null, null,
                true));

        assertThat(view.name()).isEqualTo("Renamed");
        assertThat(view.maxRows()).isNull();
        assertThat(view.maxBytes()).isEqualTo(1_024L);
        assertThat(view.windowMinutes()).isEqualTo(120);
        assertThat(view.breachAction()).isEqualTo(DataBudgetBreachAction.REJECT);
        assertThat(view.warnThresholdPercent()).isEqualTo(50);
    }

    @Test
    void updateRejectsUnknownBudget() {
        var id = UUID.randomUUID();
        when(dataBudgetRepository.findByIdAndOrganizationId(id, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, datasourceId, orgId,
                new DataBudgetCommand("n", 1L, null, null, null, null, null, null, null, null)))
                .isInstanceOf(DataBudgetNotFoundException.class);
    }

    @Test
    void updateRejectsBudgetOfAnotherDatasource() {
        var entity = entity();
        entity.setDatasourceId(UUID.randomUUID());
        when(dataBudgetRepository.findByIdAndOrganizationId(entity.getId(), orgId))
                .thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.update(entity.getId(), datasourceId, orgId,
                new DataBudgetCommand("n", 1L, null, null, null, null, null, null, null, null)))
                .isInstanceOf(DataBudgetNotFoundException.class);
    }

    @Test
    void deleteRemovesTheBudget() {
        var entity = entity();
        when(dataBudgetRepository.findByIdAndOrganizationId(entity.getId(), orgId))
                .thenReturn(Optional.of(entity));

        service.delete(entity.getId(), datasourceId, orgId);

        verify(dataBudgetRepository).delete(entity);
    }

    private void assertInvalid(DataBudgetCommand command, String key) {
        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalDataBudgetException.class)
                .hasMessage(key);
    }

    private DatasourceEntity datasource(UUID ownerOrgId) {
        var org = new OrganizationEntity();
        org.setId(ownerOrgId);
        var ds = new DatasourceEntity();
        ds.setId(datasourceId);
        ds.setOrganization(org);
        return ds;
    }

    private DataBudgetEntity entity() {
        var entity = new DataBudgetEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setDatasourceId(datasourceId);
        entity.setName("Analysts daily");
        entity.setMaxRows(100_000L);
        entity.setWindowMinutes(1440);
        entity.setBreachAction(DataBudgetBreachAction.REQUIRE_REVIEW);
        entity.setEnabled(true);
        return entity;
    }
}
