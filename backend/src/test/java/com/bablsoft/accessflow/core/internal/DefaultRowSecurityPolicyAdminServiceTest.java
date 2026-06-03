package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CreateRowSecurityPolicyCommand;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.IllegalRowSecurityPolicyException;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.RowSecurityPolicyNotFoundException;
import com.bablsoft.accessflow.core.api.RowSecurityValueType;
import com.bablsoft.accessflow.core.api.UpdateRowSecurityPolicyCommand;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.RowSecurityPolicyEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RowSecurityPolicyRepository;
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
class DefaultRowSecurityPolicyAdminServiceTest {

    @Mock RowSecurityPolicyRepository rowSecurityPolicyRepository;
    @Mock DatasourceRepository datasourceRepository;
    @Mock UserRepository userRepository;
    @Mock UserGroupRepository userGroupRepository;
    @Mock MessageSource messageSource;

    private DefaultRowSecurityPolicyAdminService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultRowSecurityPolicyAdminService(rowSecurityPolicyRepository,
                datasourceRepository, userRepository, userGroupRepository, messageSource);
        when(messageSource.getMessage(any(), any(), any())).thenReturn("error");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(datasource(orgId)));
        when(rowSecurityPolicyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void listMapsEntitiesToViews() {
        var entity = entity();
        entity.setAppliesToRoles(new String[]{"ANALYST"});
        when(rowSecurityPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdOrderByCreatedAtAsc(orgId, datasourceId))
                .thenReturn(List.of(entity));

        var result = service.listForDatasource(datasourceId, orgId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().tableName()).isEqualTo("orders");
        assertThat(result.getFirst().appliesToRoles()).containsExactly("ANALYST");
    }

    @Test
    void listRejectsDatasourceFromOtherOrg() {
        when(datasourceRepository.findById(datasourceId))
                .thenReturn(Optional.of(datasource(UUID.randomUUID())));

        assertThatThrownBy(() -> service.listForDatasource(datasourceId, orgId))
                .isInstanceOf(DatasourceNotFoundException.class);
    }

    @Test
    void createPersistsNormalizedPolicyAndStripsVariableColon() {
        var userId = UUID.randomUUID();
        when(userRepository.findAllByOrganization_IdAndIdIn(eq(orgId), anyList()))
                .thenReturn(List.of(new UserEntity()));
        var command = new CreateRowSecurityPolicyCommand(" orders ", " region ",
                RowSecurityOperator.EQUALS, RowSecurityValueType.VARIABLE, ":user.region",
                List.of("analyst"), List.of(), List.of(userId), null);

        var view = service.create(datasourceId, orgId, command);

        var captor = ArgumentCaptor.forClass(RowSecurityPolicyEntity.class);
        verify(rowSecurityPolicyRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getTableName()).isEqualTo("orders");
        assertThat(saved.getColumnName()).isEqualTo("region");
        assertThat(saved.getValueExpression()).isEqualTo("user.region");
        assertThat(saved.getAppliesToRoles()).containsExactly("ANALYST");
        assertThat(saved.getAppliesToUserIds()).containsExactly(userId);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(view.operator()).isEqualTo(RowSecurityOperator.EQUALS);
    }

    @Test
    void createKeepsLiteralValueAsIs() {
        var command = create(RowSecurityOperator.EQUALS, RowSecurityValueType.LITERAL, "EU");
        var view = service.create(datasourceId, orgId, command);
        assertThat(view.valueExpression()).isEqualTo("EU");
    }

    @Test
    void createRejectsBlankTable() {
        var command = new CreateRowSecurityPolicyCommand("  ", "region", RowSecurityOperator.EQUALS,
                RowSecurityValueType.LITERAL, "EU", List.of(), List.of(), List.of(), true);
        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalRowSecurityPolicyException.class);
        verify(rowSecurityPolicyRepository, never()).save(any());
    }

    @Test
    void createRejectsBlankColumn() {
        var command = new CreateRowSecurityPolicyCommand("orders", " ", RowSecurityOperator.EQUALS,
                RowSecurityValueType.LITERAL, "EU", List.of(), List.of(), List.of(), true);
        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalRowSecurityPolicyException.class);
    }

    @Test
    void createRejectsNullOperator() {
        var command = new CreateRowSecurityPolicyCommand("orders", "region", null,
                RowSecurityValueType.LITERAL, "EU", List.of(), List.of(), List.of(), true);
        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalRowSecurityPolicyException.class);
    }

    @Test
    void createRejectsNullValueType() {
        var command = new CreateRowSecurityPolicyCommand("orders", "region",
                RowSecurityOperator.EQUALS, null, "EU", List.of(), List.of(), List.of(), true);
        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalRowSecurityPolicyException.class);
    }

    @Test
    void createRejectsBlankValue() {
        var command = new CreateRowSecurityPolicyCommand("orders", "region",
                RowSecurityOperator.EQUALS, RowSecurityValueType.LITERAL, "  ",
                List.of(), List.of(), List.of(), true);
        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalRowSecurityPolicyException.class);
    }

    @Test
    void createRejectsVariableOutsideUserNamespace() {
        var command = create(RowSecurityOperator.EQUALS, RowSecurityValueType.VARIABLE, "sys.region");
        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalRowSecurityPolicyException.class);
    }

    @Test
    void createRejectsGroupsVariableWithScalarOperator() {
        var command = create(RowSecurityOperator.EQUALS, RowSecurityValueType.VARIABLE,
                "user.groups");
        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalRowSecurityPolicyException.class);
    }

    @Test
    void createAcceptsGroupsVariableWithInOperator() {
        var command = create(RowSecurityOperator.IN, RowSecurityValueType.VARIABLE, "user.groups");
        var view = service.create(datasourceId, orgId, command);
        assertThat(view.valueExpression()).isEqualTo("user.groups");
    }

    @Test
    void createRejectsUnknownAppliesToRole() {
        var command = new CreateRowSecurityPolicyCommand("orders", "region",
                RowSecurityOperator.EQUALS, RowSecurityValueType.LITERAL, "EU",
                List.of("WIZARD"), List.of(), List.of(), true);
        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalRowSecurityPolicyException.class);
    }

    @Test
    void createRejectsAppliesUserNotInOrganization() {
        when(userRepository.findAllByOrganization_IdAndIdIn(eq(orgId), anyList()))
                .thenReturn(List.of());
        var command = new CreateRowSecurityPolicyCommand("orders", "region",
                RowSecurityOperator.EQUALS, RowSecurityValueType.LITERAL, "EU",
                List.of(), List.of(), List.of(UUID.randomUUID()), true);
        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalRowSecurityPolicyException.class);
    }

    @Test
    void createRejectsAppliesGroupNotInOrganization() {
        when(userGroupRepository.findAllByOrganization_IdAndIdIn(eq(orgId), anyList()))
                .thenReturn(List.of());
        var command = new CreateRowSecurityPolicyCommand("orders", "region",
                RowSecurityOperator.EQUALS, RowSecurityValueType.LITERAL, "EU",
                List.of(), List.of(UUID.randomUUID()), List.of(), true);
        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalRowSecurityPolicyException.class);
    }

    @Test
    void createAcceptsAppliesGroupInOrganization() {
        when(userGroupRepository.findAllByOrganization_IdAndIdIn(eq(orgId), anyList()))
                .thenReturn(List.of(new UserGroupEntity()));
        var command = new CreateRowSecurityPolicyCommand("orders", "region",
                RowSecurityOperator.EQUALS, RowSecurityValueType.LITERAL, "EU",
                List.of(), List.of(UUID.randomUUID()), List.of(), false);

        var view = service.create(datasourceId, orgId, command);

        assertThat(view.enabled()).isFalse();
    }

    @Test
    void updateAppliesChanges() {
        var entity = entity();
        when(rowSecurityPolicyRepository.findByIdAndOrganizationId(entity.getId(), orgId))
                .thenReturn(Optional.of(entity));
        var command = new UpdateRowSecurityPolicyCommand("customers", "tenant",
                RowSecurityOperator.NOT_EQUALS, RowSecurityValueType.LITERAL, "blocked",
                List.of(), List.of(), List.of(), false);

        var view = service.update(entity.getId(), datasourceId, orgId, command);

        assertThat(view.tableName()).isEqualTo("customers");
        assertThat(view.columnName()).isEqualTo("tenant");
        assertThat(view.operator()).isEqualTo(RowSecurityOperator.NOT_EQUALS);
        assertThat(view.enabled()).isFalse();
    }

    @Test
    void updateRejectsPolicyFromDifferentDatasource() {
        var entity = entity();
        entity.setDatasourceId(UUID.randomUUID());
        when(rowSecurityPolicyRepository.findByIdAndOrganizationId(entity.getId(), orgId))
                .thenReturn(Optional.of(entity));
        var command = create(RowSecurityOperator.EQUALS, RowSecurityValueType.LITERAL, "EU");

        assertThatThrownBy(() -> service.update(entity.getId(), datasourceId, orgId,
                new UpdateRowSecurityPolicyCommand(command.tableName(), command.columnName(),
                        command.operator(), command.valueType(), command.valueExpression(),
                        List.of(), List.of(), List.of(), true)))
                .isInstanceOf(RowSecurityPolicyNotFoundException.class);
    }

    @Test
    void updateRejectsMissingPolicy() {
        var id = UUID.randomUUID();
        when(rowSecurityPolicyRepository.findByIdAndOrganizationId(id, orgId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, datasourceId, orgId,
                new UpdateRowSecurityPolicyCommand("orders", "region", RowSecurityOperator.EQUALS,
                        RowSecurityValueType.LITERAL, "EU", List.of(), List.of(), List.of(), true)))
                .isInstanceOf(RowSecurityPolicyNotFoundException.class);
    }

    @Test
    void deleteRemovesPolicy() {
        var entity = entity();
        when(rowSecurityPolicyRepository.findByIdAndOrganizationId(entity.getId(), orgId))
                .thenReturn(Optional.of(entity));

        service.delete(entity.getId(), datasourceId, orgId);

        verify(rowSecurityPolicyRepository).delete(entity);
    }

    @Test
    void deleteRejectsMissingPolicy() {
        var id = UUID.randomUUID();
        when(rowSecurityPolicyRepository.findByIdAndOrganizationId(id, orgId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id, datasourceId, orgId))
                .isInstanceOf(RowSecurityPolicyNotFoundException.class);
        verify(rowSecurityPolicyRepository, never()).delete(any());
    }

    private CreateRowSecurityPolicyCommand create(RowSecurityOperator op, RowSecurityValueType vt,
                                                  String value) {
        return new CreateRowSecurityPolicyCommand("orders", "region", op, vt, value,
                List.of(), List.of(), List.of(), true);
    }

    private DatasourceEntity datasource(UUID ownerOrgId) {
        var org = new OrganizationEntity();
        org.setId(ownerOrgId);
        var ds = new DatasourceEntity();
        ds.setId(datasourceId);
        ds.setOrganization(org);
        return ds;
    }

    private RowSecurityPolicyEntity entity() {
        var entity = new RowSecurityPolicyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setDatasourceId(datasourceId);
        entity.setTableName("orders");
        entity.setColumnName("region");
        entity.setOperator(RowSecurityOperator.EQUALS);
        entity.setValueType(RowSecurityValueType.LITERAL);
        entity.setValueExpression("EU");
        entity.setEnabled(true);
        return entity;
    }
}
