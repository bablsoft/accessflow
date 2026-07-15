package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorMaskingPolicyNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiMaskingMatcherType;
import com.bablsoft.accessflow.apigov.api.CreateApiConnectorMaskingPolicyCommand;
import com.bablsoft.accessflow.apigov.api.IllegalApiConnectorMaskingPolicyException;
import com.bablsoft.accessflow.apigov.api.UpdateApiConnectorMaskingPolicyCommand;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorMaskingPolicyEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorMaskingPolicyRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.RoleLookupService;
import com.bablsoft.accessflow.core.api.RoleView;
import com.bablsoft.accessflow.core.api.UserGroupService;
import com.bablsoft.accessflow.core.api.UserGroupView;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultApiConnectorMaskingAdminServiceTest {

    @Mock ApiConnectorMaskingPolicyRepository policyRepository;
    @Mock RoleLookupService roleLookupService;
    @Mock ApiConnectorRepository connectorRepository;
    @Mock UserQueryService userQueryService;
    @Mock UserGroupService userGroupService;
    @Mock MessageSource messageSource;

    private DefaultApiConnectorMaskingAdminService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultApiConnectorMaskingAdminService(policyRepository, roleLookupService,
                connectorRepository, userQueryService, userGroupService, new ObjectMapper(), messageSource);
        when(messageSource.getMessage(any(), any(), any())).thenReturn("error");
        when(roleLookupService.findByNameInScope(orgId, "admin"))
                .thenReturn(Optional.of(systemRole("ADMIN")));
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId))
                .thenReturn(Optional.of(connector()));
        when(policyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private RoleView systemRole(String name) {
        return new RoleView(UUID.randomUUID(), null, name, null, true, Set.of(), 0,
                Instant.now(), Instant.now());
    }

    private ApiConnectorEntity connector() {
        var c = new ApiConnectorEntity();
        c.setId(connectorId);
        c.setOrganizationId(orgId);
        return c;
    }

    private ApiConnectorMaskingPolicyEntity entity(ApiMaskingMatcherType type, String fieldRef) {
        var e = new ApiConnectorMaskingPolicyEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(orgId);
        e.setConnectorId(connectorId);
        e.setMatcherType(type);
        e.setFieldRef(fieldRef);
        e.setStrategy(MaskingStrategy.FULL);
        e.setStrategyParams("{}");
        return e;
    }

    private UserView userInOrg(UUID userId, UUID organizationId) {
        return new UserView(userId, "u@x.io", "U", UserRoleType.ANALYST, organizationId, true,
                AuthProviderType.LOCAL, null, null, "en", false, Instant.now());
    }

    @Test
    void listMapsEntitiesToViews() {
        var e = entity(ApiMaskingMatcherType.JSON_PATH, "user.ssn");
        e.setRevealToRoles(new String[]{"ADMIN"});
        when(policyRepository.findAllByOrganizationIdAndConnectorIdOrderByCreatedAt(orgId, connectorId))
                .thenReturn(List.of(e));

        var result = service.listForConnector(connectorId, orgId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().matcherType()).isEqualTo(ApiMaskingMatcherType.JSON_PATH);
        assertThat(result.getFirst().revealToRoles()).containsExactly("ADMIN");
    }

    @Test
    void listRejectsConnectorFromOtherOrg() {
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listForConnector(connectorId, orgId))
                .isInstanceOf(ApiConnectorNotFoundException.class);
    }

    @Test
    void createPersistsSchemaFieldPolicy() {
        var userId = UUID.randomUUID();
        when(userQueryService.findById(userId)).thenReturn(Optional.of(userInOrg(userId, orgId)));
        var command = new CreateApiConnectorMaskingPolicyCommand(ApiMaskingMatcherType.SCHEMA_FIELD,
                "getUser", "email", MaskingStrategy.PARTIAL, Map.of("visible_suffix", "4"),
                List.of("admin"), List.of(), List.of(userId), null);

        var view = service.create(connectorId, orgId, command);

        var captor = ArgumentCaptor.forClass(ApiConnectorMaskingPolicyEntity.class);
        verify(policyRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getMatcherType()).isEqualTo(ApiMaskingMatcherType.SCHEMA_FIELD);
        assertThat(saved.getOperationId()).isEqualTo("getUser");
        assertThat(saved.getFieldRef()).isEqualTo("email");
        assertThat(saved.getStrategyParams()).contains("visible_suffix").contains("4");
        assertThat(saved.getRevealToRoles()).containsExactly("ADMIN");
        assertThat(saved.isEnabled()).isTrue();
        assertThat(view.matcherType()).isEqualTo(ApiMaskingMatcherType.SCHEMA_FIELD);
    }

    @Test
    void createRejectsSchemaFieldWithoutOperation() {
        var command = new CreateApiConnectorMaskingPolicyCommand(ApiMaskingMatcherType.SCHEMA_FIELD,
                null, "email", MaskingStrategy.FULL, Map.of(), List.of(), List.of(), List.of(), null);

        assertThatThrownBy(() -> service.create(connectorId, orgId, command))
                .isInstanceOf(IllegalApiConnectorMaskingPolicyException.class);
    }

    @Test
    void createRejectsBlankFieldRef() {
        var command = new CreateApiConnectorMaskingPolicyCommand(ApiMaskingMatcherType.JSON_PATH,
                null, "  ", MaskingStrategy.FULL, Map.of(), List.of(), List.of(), List.of(), null);

        assertThatThrownBy(() -> service.create(connectorId, orgId, command))
                .isInstanceOf(IllegalApiConnectorMaskingPolicyException.class);
    }

    @Test
    void createRejectsNullStrategy() {
        var command = new CreateApiConnectorMaskingPolicyCommand(ApiMaskingMatcherType.JSON_PATH,
                null, "a", null, Map.of(), List.of(), List.of(), List.of(), null);

        assertThatThrownBy(() -> service.create(connectorId, orgId, command))
                .isInstanceOf(IllegalApiConnectorMaskingPolicyException.class);
    }

    @Test
    void createRejectsNullMatcher() {
        var command = new CreateApiConnectorMaskingPolicyCommand(null, null, "a", MaskingStrategy.FULL,
                Map.of(), List.of(), List.of(), List.of(), null);

        assertThatThrownBy(() -> service.create(connectorId, orgId, command))
                .isInstanceOf(IllegalApiConnectorMaskingPolicyException.class);
    }

    @Test
    void createRejectsInvalidVisibleSuffix() {
        var command = new CreateApiConnectorMaskingPolicyCommand(ApiMaskingMatcherType.JSON_PATH,
                null, "a", MaskingStrategy.PARTIAL, Map.of("visible_suffix", "0"),
                List.of(), List.of(), List.of(), null);

        assertThatThrownBy(() -> service.create(connectorId, orgId, command))
                .isInstanceOf(IllegalApiConnectorMaskingPolicyException.class);
    }

    @Test
    void createRejectsUnknownRevealRole() {
        var command = new CreateApiConnectorMaskingPolicyCommand(ApiMaskingMatcherType.JSON_PATH,
                null, "a", MaskingStrategy.FULL, Map.of(), List.of("WIZARD"), List.of(), List.of(), null);

        assertThatThrownBy(() -> service.create(connectorId, orgId, command))
                .isInstanceOf(IllegalApiConnectorMaskingPolicyException.class);
    }

    @Test
    void createRejectsRevealUserFromOtherOrg() {
        var userId = UUID.randomUUID();
        when(userQueryService.findById(userId))
                .thenReturn(Optional.of(userInOrg(userId, UUID.randomUUID())));
        var command = new CreateApiConnectorMaskingPolicyCommand(ApiMaskingMatcherType.JSON_PATH,
                null, "a", MaskingStrategy.FULL, Map.of(), List.of(), List.of(), List.of(userId), null);

        assertThatThrownBy(() -> service.create(connectorId, orgId, command))
                .isInstanceOf(IllegalApiConnectorMaskingPolicyException.class);
    }

    @Test
    void createRejectsRevealGroupNotInOrg() {
        var groupId = UUID.randomUUID();
        when(userGroupService.listAll(orgId)).thenReturn(List.of());
        var command = new CreateApiConnectorMaskingPolicyCommand(ApiMaskingMatcherType.JSON_PATH,
                null, "a", MaskingStrategy.FULL, Map.of(), List.of(), List.of(groupId), List.of(), null);

        assertThatThrownBy(() -> service.create(connectorId, orgId, command))
                .isInstanceOf(IllegalApiConnectorMaskingPolicyException.class);
    }

    @Test
    void createAcceptsRevealGroupInOrg() {
        var groupId = UUID.randomUUID();
        when(userGroupService.listAll(orgId)).thenReturn(List.of(
                new UserGroupView(groupId, orgId, "g", null, 0, Instant.now(), Instant.now())));
        var command = new CreateApiConnectorMaskingPolicyCommand(ApiMaskingMatcherType.JSON_PATH,
                null, "a", MaskingStrategy.FULL, Map.of(), List.of(), List.of(groupId), List.of(), null);

        var view = service.create(connectorId, orgId, command);

        assertThat(view.revealToGroupIds()).containsExactly(groupId);
    }

    @Test
    void updateModifiesExistingPolicy() {
        var policyId = UUID.randomUUID();
        var existing = entity(ApiMaskingMatcherType.JSON_PATH, "old");
        existing.setId(policyId);
        when(policyRepository.findByIdAndOrganizationIdAndConnectorId(policyId, orgId, connectorId))
                .thenReturn(Optional.of(existing));
        var command = new UpdateApiConnectorMaskingPolicyCommand(ApiMaskingMatcherType.REGEX, null,
                "new", MaskingStrategy.HASH, Map.of(), List.of(), List.of(), List.of(), false);

        var view = service.update(policyId, connectorId, orgId, command);

        assertThat(view.matcherType()).isEqualTo(ApiMaskingMatcherType.REGEX);
        assertThat(view.fieldRef()).isEqualTo("new");
        assertThat(view.enabled()).isFalse();
    }

    @Test
    void updateRejectsMissingPolicy() {
        var policyId = UUID.randomUUID();
        when(policyRepository.findByIdAndOrganizationIdAndConnectorId(policyId, orgId, connectorId))
                .thenReturn(Optional.empty());
        var command = new UpdateApiConnectorMaskingPolicyCommand(ApiMaskingMatcherType.JSON_PATH, null,
                "a", MaskingStrategy.FULL, Map.of(), List.of(), List.of(), List.of(), null);

        assertThatThrownBy(() -> service.update(policyId, connectorId, orgId, command))
                .isInstanceOf(ApiConnectorMaskingPolicyNotFoundException.class);
    }

    @Test
    void deleteRemovesPolicy() {
        var policyId = UUID.randomUUID();
        var existing = entity(ApiMaskingMatcherType.JSON_PATH, "a");
        existing.setId(policyId);
        when(policyRepository.findByIdAndOrganizationIdAndConnectorId(policyId, orgId, connectorId))
                .thenReturn(Optional.of(existing));

        service.delete(policyId, connectorId, orgId);

        verify(policyRepository).delete(existing);
    }
}
