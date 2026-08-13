package com.bablsoft.accessflow.scim.internal;

import com.bablsoft.accessflow.core.api.UserGroupMembershipSourceType;
import com.bablsoft.accessflow.core.api.UserGroupMembershipView;
import com.bablsoft.accessflow.core.api.UserGroupNameAlreadyExistsException;
import com.bablsoft.accessflow.core.api.UserGroupNotFoundException;
import com.bablsoft.accessflow.core.api.UserGroupService;
import com.bablsoft.accessflow.core.api.UserGroupView;
import com.bablsoft.accessflow.scim.api.ScimPrincipal;
import com.bablsoft.accessflow.scim.internal.protocol.ScimGroupResource;
import com.bablsoft.accessflow.scim.internal.protocol.ScimInvalidValueException;
import com.bablsoft.accessflow.scim.internal.protocol.ScimMemberRef;
import com.bablsoft.accessflow.scim.internal.protocol.ScimPatchRequest;
import com.bablsoft.accessflow.scim.internal.protocol.ScimResourceNotFoundException;
import com.bablsoft.accessflow.scim.internal.protocol.ScimUniquenessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScimGroupOrchestratorTest {

    private static final String BASE = "https://af.example.com/scim/v2";

    @Mock UserGroupService userGroupService;

    ScimGroupOrchestrator orchestrator;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID orgId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();
    private final ScimPrincipal principal =
            new ScimPrincipal(orgId, UUID.randomUUID(), "entra-prod");

    @BeforeEach
    void setUp() {
        orchestrator = new ScimGroupOrchestrator(userGroupService);
        lenient().when(userGroupService.getGroup(groupId, orgId))
                .thenReturn(groupView(groupId, "Engineers", "grp-ext-1"));
        lenient().when(userGroupService.listMembers(groupId, orgId)).thenReturn(List.of());
    }

    @Test
    void createGroupWithMembersUsesScimSource() {
        var memberId = UUID.randomUUID();
        when(userGroupService.listAll(orgId)).thenReturn(List.of());
        when(userGroupService.createGroup(any()))
                .thenReturn(groupView(groupId, "Engineers", "grp-ext-1"));

        var created = orchestrator.create(principal, new ScimGroupResource(null, null,
                "grp-ext-1", "Engineers",
                List.of(new ScimMemberRef(memberId.toString(), null)), null), BASE);

        verify(userGroupService).replaceMembersBySource(eq(groupId), eq(orgId),
                eq(List.of(memberId)), eq(UserGroupMembershipSourceType.SCIM));
        assertThat(created.displayName()).isEqualTo("Engineers");
        assertThat(created.meta().location()).isEqualTo(BASE + "/Groups/" + groupId);
    }

    @Test
    void createWithoutDisplayNameIsInvalidValue() {
        assertThatThrownBy(() -> orchestrator.create(principal,
                new ScimGroupResource(null, null, null, " ", null, null), BASE))
                .isInstanceOf(ScimInvalidValueException.class);
    }

    @Test
    void createDuplicateNameBecomesUniqueness() {
        when(userGroupService.createGroup(any()))
                .thenThrow(new UserGroupNameAlreadyExistsException("Engineers"));

        assertThatThrownBy(() -> orchestrator.create(principal,
                new ScimGroupResource(null, null, null, "Engineers", null, null), BASE))
                .isInstanceOf(ScimUniquenessException.class);
    }

    @Test
    void createDuplicateExternalIdBecomesUniqueness() {
        when(userGroupService.listAll(orgId))
                .thenReturn(List.of(groupView(UUID.randomUUID(), "Other", "grp-ext-1")));

        assertThatThrownBy(() -> orchestrator.create(principal,
                new ScimGroupResource(null, null, "grp-ext-1", "Engineers", null, null), BASE))
                .isInstanceOf(ScimUniquenessException.class);
    }

    @Test
    void listFiltersByDisplayNameCaseInsensitive() {
        when(userGroupService.listAll(orgId)).thenReturn(List.of(
                groupView(groupId, "Engineers", null),
                groupView(UUID.randomUUID(), "Ops", null)));

        var response = orchestrator.list(principal, "displayName eq \"engineers\"", 1, 100, BASE);

        assertThat(response.totalResults()).isEqualTo(1);
        assertThat(response.resources().get(0).displayName()).isEqualTo("Engineers");
    }

    @Test
    void getIncludesMembers() {
        var memberId = UUID.randomUUID();
        when(userGroupService.listMembers(groupId, orgId)).thenReturn(List.of(
                new UserGroupMembershipView(memberId, groupId, "jane@example.com", "Jane",
                        UserGroupMembershipSourceType.SCIM, Instant.now())));

        var resource = orchestrator.get(principal, groupId, BASE);

        assertThat(resource.members()).hasSize(1);
        assertThat(resource.members().get(0).value()).isEqualTo(memberId.toString());
    }

    @Test
    void getUnknownGroupIs404() {
        var unknown = UUID.randomUUID();
        when(userGroupService.getGroup(unknown, orgId))
                .thenThrow(new UserGroupNotFoundException(unknown));

        assertThatThrownBy(() -> orchestrator.get(principal, unknown, BASE))
                .isInstanceOf(ScimResourceNotFoundException.class);
    }

    @Test
    void replaceRenamesAndReplacesScimMembers() {
        var memberId = UUID.randomUUID();
        when(userGroupService.updateGroup(eq(groupId), eq(orgId), any()))
                .thenReturn(groupView(groupId, "Platform", null));

        orchestrator.replace(principal, groupId, new ScimGroupResource(null, null, null,
                "Platform", List.of(new ScimMemberRef(memberId.toString(), null)), null), BASE);

        verify(userGroupService).replaceMembersBySource(eq(groupId), eq(orgId),
                eq(List.of(memberId)), eq(UserGroupMembershipSourceType.SCIM));
    }

    @Test
    void entraShapedMemberAddPatch() {
        var memberId = UUID.randomUUID();
        var patch = patchRequest("""
                {"Operations":[{"op":"Add","path":"members",
                    "value":[{"value":"%s"}]}]}
                """.formatted(memberId));

        orchestrator.patch(principal, groupId, patch, BASE);

        verify(userGroupService).addMember(groupId, memberId, orgId,
                UserGroupMembershipSourceType.SCIM);
    }

    @Test
    void entraShapedFilteredMemberRemovePatch() {
        var memberId = UUID.randomUUID();
        var patch = patchRequest("""
                {"Operations":[{"op":"Remove","path":"members[value eq \\"%s\\"]"}]}
                """.formatted(memberId));

        orchestrator.patch(principal, groupId, patch, BASE);

        verify(userGroupService).removeMemberBySource(groupId, memberId, orgId,
                UserGroupMembershipSourceType.SCIM);
    }

    @Test
    void oktaShapedMemberReplacePatch() {
        var memberId = UUID.randomUUID();
        var patch = patchRequest("""
                {"Operations":[{"op":"replace","path":"members",
                    "value":[{"value":"%s"}]}]}
                """.formatted(memberId));

        orchestrator.patch(principal, groupId, patch, BASE);

        verify(userGroupService).replaceMembersBySource(eq(groupId), eq(orgId),
                eq(List.of(memberId)), eq(UserGroupMembershipSourceType.SCIM));
    }

    @Test
    void patchRenameViaValueObject() {
        var patch = patchRequest("""
                {"Operations":[{"op":"replace","value":{"displayName":"Platform"}}]}
                """);
        when(userGroupService.updateGroup(eq(groupId), eq(orgId), any()))
                .thenReturn(groupView(groupId, "Platform", null));

        orchestrator.patch(principal, groupId, patch, BASE);

        verify(userGroupService).updateGroup(eq(groupId), eq(orgId), any());
    }

    @Test
    void patchInvalidMemberIdIsInvalidValue() {
        var patch = patchRequest("""
                {"Operations":[{"op":"add","path":"members","value":[{"value":"not-a-uuid"}]}]}
                """);

        assertThatThrownBy(() -> orchestrator.patch(principal, groupId, patch, BASE))
                .isInstanceOf(ScimInvalidValueException.class);
    }

    @Test
    void deleteReturnsTheDeletedView() {
        var deleted = orchestrator.delete(principal, groupId);

        verify(userGroupService).deleteGroup(groupId, orgId);
        assertThat(deleted.name()).isEqualTo("Engineers");
    }

    @Test
    void replaceMembersResultIgnoredWhenMembersNull() {
        when(userGroupService.updateGroup(eq(groupId), eq(orgId), any()))
                .thenReturn(groupView(groupId, "Engineers", null));

        orchestrator.replace(principal, groupId, new ScimGroupResource(null, null, null,
                "Engineers", null, null), BASE);

        verify(userGroupService, org.mockito.Mockito.never())
                .replaceMembersBySource(any(), any(), any(), any());
    }

    private ScimPatchRequest patchRequest(String json) {
        return objectMapper.readValue(json, ScimPatchRequest.class);
    }

    private static UserGroupView groupView(UUID id, String name, String externalId) {
        return new UserGroupView(id, UUID.randomUUID(), name, null, 0,
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-02T00:00:00Z"),
                externalId);
    }
}
