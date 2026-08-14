package com.bablsoft.accessflow.scim.internal;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CreateExternalUserCommand;
import com.bablsoft.accessflow.core.api.DirectoryPage;
import com.bablsoft.accessflow.core.api.EmailAlreadyExistsException;
import com.bablsoft.accessflow.core.api.ExternalUserDirectoryService;
import com.bablsoft.accessflow.core.api.UpdateExternalUserCommand;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.scim.api.ScimConfigService;
import com.bablsoft.accessflow.scim.api.ScimConfigView;
import com.bablsoft.accessflow.scim.api.ScimPrincipal;
import com.bablsoft.accessflow.scim.internal.protocol.ScimEmail;
import com.bablsoft.accessflow.scim.internal.protocol.ScimInvalidFilterException;
import com.bablsoft.accessflow.scim.internal.protocol.ScimInvalidPathException;
import com.bablsoft.accessflow.scim.internal.protocol.ScimInvalidValueException;
import com.bablsoft.accessflow.scim.internal.protocol.ScimName;
import com.bablsoft.accessflow.scim.internal.protocol.ScimPatchRequest;
import com.bablsoft.accessflow.scim.internal.protocol.ScimResourceNotFoundException;
import com.bablsoft.accessflow.scim.internal.protocol.ScimUniquenessException;
import com.bablsoft.accessflow.scim.internal.protocol.ScimUserResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScimUserOrchestratorTest {

    private static final String BASE = "https://af.example.com/scim/v2";

    @Mock ExternalUserDirectoryService directory;
    @Mock ScimConfigService configService;

    ScimUserOrchestrator orchestrator;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final ScimPrincipal principal =
            new ScimPrincipal(orgId, UUID.randomUUID(), "okta-prod");

    @BeforeEach
    void setUp() {
        orchestrator = new ScimUserOrchestrator(directory, configService);
        lenient().when(configService.get(orgId)).thenReturn(defaultConfig());
    }

    @Test
    void createMapsUserNameToEmailAndDefaultRole() {
        when(directory.createExternal(any())).thenAnswer(inv -> {
            CreateExternalUserCommand cmd = inv.getArgument(0);
            return userView(cmd.email(), cmd.displayName(), cmd.scimExternalId(), true);
        });

        var created = orchestrator.create(principal, oktaUser("jane@example.com", "Jane Doe",
                "00u1abcd", true), BASE);

        var captor = ArgumentCaptor.forClass(CreateExternalUserCommand.class);
        verify(directory).createExternal(captor.capture());
        assertThat(captor.getValue().email()).isEqualTo("jane@example.com");
        assertThat(captor.getValue().displayName()).isEqualTo("Jane Doe");
        assertThat(captor.getValue().scimExternalId()).isEqualTo("00u1abcd");
        assertThat(captor.getValue().defaultRole()).isEqualTo(UserRoleType.ANALYST);
        assertThat(created.userName()).isEqualTo("jane@example.com");
        assertThat(created.meta().location()).startsWith(BASE + "/Users/");
        assertThat(created.schemas())
                .containsExactly("urn:ietf:params:scim:schemas:core:2.0:User");
    }

    @Test
    void createWithEmailsPrimaryMappingReadsPrimaryEmail() {
        when(configService.get(orgId)).thenReturn(config("emails.primary", "displayName"));
        when(directory.createExternal(any())).thenAnswer(inv -> {
            CreateExternalUserCommand cmd = inv.getArgument(0);
            return userView(cmd.email(), cmd.displayName(), null, true);
        });

        var resource = new ScimUserResource(null, null, null, "jdoe", "Jane", null,
                List.of(new ScimEmail("secondary@example.com", "home", false),
                        new ScimEmail("primary@example.com", "work", true)),
                true, null);
        orchestrator.create(principal, resource, BASE);

        var captor = ArgumentCaptor.forClass(CreateExternalUserCommand.class);
        verify(directory).createExternal(captor.capture());
        assertThat(captor.getValue().email()).isEqualTo("primary@example.com");
    }

    @Test
    void createWithoutEmailSourceIsInvalidValue() {
        var resource = new ScimUserResource(null, null, null, null, "Jane", null, null, true, null);

        assertThatThrownBy(() -> orchestrator.create(principal, resource, BASE))
                .isInstanceOf(ScimInvalidValueException.class);
    }

    @Test
    void createDuplicateEmailBecomesUniqueness() {
        when(directory.createExternal(any()))
                .thenThrow(new EmailAlreadyExistsException("jane@example.com"));

        assertThatThrownBy(() -> orchestrator.create(principal,
                oktaUser("jane@example.com", "Jane", null, true), BASE))
                .isInstanceOf(ScimUniquenessException.class);
    }

    @Test
    void listWithoutFilterPagesTheDirectory() {
        when(directory.list(orgId, 0, 100)).thenReturn(
                new DirectoryPage<>(List.of(userView("a@x.io", "A", null, true)), 5));

        var response = orchestrator.list(principal, null, 1, 100, BASE);

        assertThat(response.totalResults()).isEqualTo(5);
        assertThat(response.startIndex()).isEqualTo(1);
        assertThat(response.resources()).hasSize(1);
        assertThat(response.schemas())
                .containsExactly("urn:ietf:params:scim:api:messages:2.0:ListResponse");
    }

    @Test
    void listWithUserNameFilterLooksUpByEmail() {
        when(directory.findByEmail(orgId, "jane@example.com"))
                .thenReturn(Optional.of(userView("jane@example.com", "Jane", null, true)));

        var response = orchestrator.list(principal, "userName eq \"jane@example.com\"", 1, 100,
                BASE);

        assertThat(response.totalResults()).isEqualTo(1);
        assertThat(response.resources().get(0).userName()).isEqualTo("jane@example.com");
    }

    @Test
    void listWithUnmatchedFilterReturnsEmpty() {
        when(directory.findByExternalId(orgId, "nope")).thenReturn(Optional.empty());

        var response = orchestrator.list(principal, "externalId eq \"nope\"", 1, 100, BASE);

        assertThat(response.totalResults()).isZero();
        assertThat(response.resources()).isEmpty();
    }

    @Test
    void listWithUnsupportedFilterAttributeIsInvalidFilter() {
        assertThatThrownBy(() -> orchestrator.list(principal, "title eq \"boss\"", 1, 100, BASE))
                .isInstanceOf(ScimInvalidFilterException.class);
    }

    @Test
    void getUnknownUserIs404() {
        when(directory.findById(orgId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orchestrator.get(principal, userId, BASE))
                .isInstanceOf(ScimResourceNotFoundException.class);
    }

    @Test
    void replaceUpdatesOnlyScimOwnedAttributes() {
        when(directory.findById(orgId, userId))
                .thenReturn(Optional.of(userView("old@example.com", "Old", null, true)));
        when(directory.updateExternal(eq(orgId), eq(userId), any()))
                .thenAnswer(inv -> userView("new@example.com", "New", "ext-9", true));

        var result = orchestrator.replace(principal, userId,
                oktaUser("new@example.com", "New", "ext-9", true), BASE);

        var captor = ArgumentCaptor.forClass(UpdateExternalUserCommand.class);
        verify(directory).updateExternal(eq(orgId), eq(userId), captor.capture());
        assertThat(captor.getValue().email()).isEqualTo("new@example.com");
        assertThat(captor.getValue().active()).isTrue();
        assertThat(result.deactivated()).isFalse();
    }

    @Test
    void oktaShapedPatchDeactivates() {
        // Okta: {"schemas":[PatchOp],"Operations":[{"op":"replace","value":{"active":false}}]}
        when(directory.findById(orgId, userId))
                .thenReturn(Optional.of(userView("jane@example.com", "Jane", null, true)));
        when(directory.updateExternal(eq(orgId), eq(userId), any()))
                .thenAnswer(inv -> userView("jane@example.com", "Jane", null, false));
        var patch = patchRequest("""
                {"Operations":[{"op":"replace","value":{"active":false}}]}
                """);

        var result = orchestrator.patch(principal, userId, patch, BASE);

        var captor = ArgumentCaptor.forClass(UpdateExternalUserCommand.class);
        verify(directory).updateExternal(eq(orgId), eq(userId), captor.capture());
        assertThat(captor.getValue().active()).isFalse();
        assertThat(result.deactivated()).isTrue();
    }

    @Test
    void entraShapedPatchWithStringBooleanAndPathDeactivates() {
        // Entra: {"Operations":[{"op":"Replace","path":"active","value":"False"}]}
        when(directory.findById(orgId, userId))
                .thenReturn(Optional.of(userView("jane@example.com", "Jane", null, true)));
        when(directory.updateExternal(eq(orgId), eq(userId), any()))
                .thenAnswer(inv -> userView("jane@example.com", "Jane", null, false));
        var patch = patchRequest("""
                {"Operations":[{"op":"Replace","path":"active","value":"False"}]}
                """);

        var result = orchestrator.patch(principal, userId, patch, BASE);

        assertThat(result.deactivated()).isTrue();
    }

    @Test
    void patchIgnoresUnknownAttributesLikePassword() {
        when(directory.findById(orgId, userId))
                .thenReturn(Optional.of(userView("jane@example.com", "Jane", null, true)));
        when(directory.updateExternal(eq(orgId), eq(userId), any()))
                .thenAnswer(inv -> userView("jane@example.com", "Renamed", null, true));
        var patch = patchRequest("""
                {"Operations":[{"op":"replace","value":
                    {"password":"hunter2","displayName":"Renamed"}}]}
                """);

        orchestrator.patch(principal, userId, patch, BASE);

        var captor = ArgumentCaptor.forClass(UpdateExternalUserCommand.class);
        verify(directory).updateExternal(eq(orgId), eq(userId), captor.capture());
        assertThat(captor.getValue().displayName()).isEqualTo("Renamed");
        assertThat(captor.getValue().email()).isNull();
        assertThat(captor.getValue().active()).isNull();
    }

    @Test
    void patchRemoveOpIsInvalidPath() {
        when(directory.findById(orgId, userId))
                .thenReturn(Optional.of(userView("jane@example.com", "Jane", null, true)));
        var patch = patchRequest("""
                {"Operations":[{"op":"remove","path":"displayName"}]}
                """);

        assertThatThrownBy(() -> orchestrator.patch(principal, userId, patch, BASE))
                .isInstanceOf(ScimInvalidPathException.class);
    }

    @Test
    void patchWithNonBooleanActiveIsInvalidValue() {
        when(directory.findById(orgId, userId))
                .thenReturn(Optional.of(userView("jane@example.com", "Jane", null, true)));
        var patch = patchRequest("""
                {"Operations":[{"op":"replace","path":"active","value":"maybe"}]}
                """);

        assertThatThrownBy(() -> orchestrator.patch(principal, userId, patch, BASE))
                .isInstanceOf(ScimInvalidValueException.class);
    }

    @Test
    void deleteDeactivatesOnceAndIsIdempotent() {
        when(directory.findById(orgId, userId))
                .thenReturn(Optional.of(userView("jane@example.com", "Jane", null, true)));

        assertThat(orchestrator.delete(principal, userId)).isTrue();
        verify(directory).updateExternal(eq(orgId), eq(userId), any());

        when(directory.findById(orgId, userId))
                .thenReturn(Optional.of(userView("jane@example.com", "Jane", null, false)));
        assertThat(orchestrator.delete(principal, userId)).isFalse();
    }

    @Test
    void responseNeverCarriesPasswordData() {
        var resource = ScimUserOrchestrator.toResource(
                userView("jane@example.com", "Jane", "ext-1", true), defaultConfig(), BASE);

        var json = objectMapper.writeValueAsString(resource);
        assertThat(json).doesNotContainIgnoringCase("password");
        assertThat(json).contains("\"userName\":\"jane@example.com\"");
        assertThat(json).contains("\"externalId\":\"ext-1\"");
    }

    private ScimPatchRequest patchRequest(String json) {
        return objectMapper.readValue(json, ScimPatchRequest.class);
    }

    private static ScimUserResource oktaUser(String userName, String displayName,
                                             String externalId, Boolean active) {
        return new ScimUserResource(
                List.of("urn:ietf:params:scim:schemas:core:2.0:User"),
                null, externalId, userName, displayName,
                new ScimName(displayName, "Jane", "Doe"),
                List.of(new ScimEmail(userName, "work", true)),
                active, null);
    }

    private UserView userView(String email, String displayName, String externalId,
                              boolean active) {
        return new UserView(userId, email, displayName, UserRoleType.ANALYST, null, "ANALYST",
                orgId, active, AuthProviderType.SCIM, null, null, null, false, false,
                Instant.parse("2026-08-01T00:00:00Z"), externalId,
                Instant.parse("2026-08-02T00:00:00Z"));
    }

    private static ScimConfigView defaultConfig() {
        return config("userName", "displayName");
    }

    private static ScimConfigView config(String attrEmail, String attrDisplayName) {
        return new ScimConfigView(null, null, true, attrEmail, attrDisplayName,
                UserRoleType.ANALYST, null, null);
    }
}
