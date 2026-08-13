package com.bablsoft.accessflow.scim.internal;

import com.bablsoft.accessflow.core.api.CreateUserGroupCommand;
import com.bablsoft.accessflow.core.api.UpdateUserGroupCommand;
import com.bablsoft.accessflow.core.api.UserGroupMembershipSourceType;
import com.bablsoft.accessflow.core.api.UserGroupNameAlreadyExistsException;
import com.bablsoft.accessflow.core.api.UserGroupNotFoundException;
import com.bablsoft.accessflow.core.api.UserGroupService;
import com.bablsoft.accessflow.core.api.UserGroupView;
import com.bablsoft.accessflow.core.api.UserNotFoundException;
import com.bablsoft.accessflow.scim.api.ScimPrincipal;
import com.bablsoft.accessflow.scim.internal.protocol.ScimFilterParser;
import com.bablsoft.accessflow.scim.internal.protocol.ScimGroupResource;
import com.bablsoft.accessflow.scim.internal.protocol.ScimInvalidFilterException;
import com.bablsoft.accessflow.scim.internal.protocol.ScimInvalidPathException;
import com.bablsoft.accessflow.scim.internal.protocol.ScimInvalidValueException;
import com.bablsoft.accessflow.scim.internal.protocol.ScimListResponse;
import com.bablsoft.accessflow.scim.internal.protocol.ScimMemberRef;
import com.bablsoft.accessflow.scim.internal.protocol.ScimMeta;
import com.bablsoft.accessflow.scim.internal.protocol.ScimPatchRequest;
import com.bablsoft.accessflow.scim.internal.protocol.ScimResourceNotFoundException;
import com.bablsoft.accessflow.scim.internal.protocol.ScimSchemas;
import com.bablsoft.accessflow.scim.internal.protocol.ScimUniquenessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Maps the SCIM Group wire contract onto {@link UserGroupService} (#621). All member writes carry
 * {@code source=SCIM}, so MANUAL and SSO-IDP memberships are never touched. Group listings are
 * resolved in memory over {@code listAll} — per-org group counts are small by construction.
 */
@Service
@RequiredArgsConstructor
public class ScimGroupOrchestrator {

    static final String RESOURCE_TYPE = "Group";

    /** Entra's remove-one-member path form: {@code members[value eq "<uuid>"]}. */
    private static final Pattern MEMBERS_VALUE_PATH = Pattern.compile(
            "^members\\[value\\s+eq\\s+\"((?:[^\"\\\\]|\\\\.)*)\"\\]$", Pattern.CASE_INSENSITIVE);

    private final UserGroupService userGroupService;

    public ScimListResponse<ScimGroupResource> list(ScimPrincipal principal, String filterExpression,
                                             int startIndex, int count, String baseUrl) {
        var filter = ScimFilterParser.parse(filterExpression);
        var all = userGroupService.listAll(principal.organizationId());
        List<UserGroupView> matches;
        if (filter == null) {
            matches = all;
        } else {
            matches = switch (filter.attribute()) {
                case "displayname" -> all.stream()
                        .filter(g -> g.name() != null && g.name().equalsIgnoreCase(filter.value()))
                        .toList();
                case "externalid" -> all.stream()
                        .filter(g -> filter.value().equals(g.scimExternalId()))
                        .toList();
                default -> throw new ScimInvalidFilterException(
                        "Unsupported filter attribute: " + filter.attribute());
            };
        }
        var normalizedStart = Math.max(1, startIndex);
        var normalizedCount = Math.clamp(count, 1, 200);
        var window = matches.stream()
                .skip(normalizedStart - 1L)
                .limit(normalizedCount)
                .map(g -> toResource(principal, g, baseUrl, true))
                .toList();
        return new ScimListResponse<>(List.of(ScimSchemas.LIST_RESPONSE), matches.size(),
                normalizedStart, window.size(), window);
    }

    public ScimGroupResource create(ScimPrincipal principal, ScimGroupResource resource, String baseUrl) {
        if (resource.displayName() == null || resource.displayName().isBlank()) {
            throw new ScimInvalidValueException("Missing required attribute: displayName");
        }
        var externalId = blankToNull(resource.externalId());
        if (externalId != null && findByExternalId(principal, externalId) != null) {
            throw new ScimUniquenessException("A group with this externalId already exists");
        }
        UserGroupView created;
        try {
            created = userGroupService.createGroup(new CreateUserGroupCommand(
                    principal.organizationId(), resource.displayName(), null, externalId));
        } catch (UserGroupNameAlreadyExistsException ex) {
            throw new ScimUniquenessException("A group with this displayName already exists");
        }
        if (resource.members() != null && !resource.members().isEmpty()) {
            userGroupService.replaceMembersBySource(created.id(), principal.organizationId(),
                    memberIds(resource.members()), UserGroupMembershipSourceType.SCIM);
        }
        return get(principal, created.id(), baseUrl);
    }

    public ScimGroupResource get(ScimPrincipal principal, UUID id, String baseUrl) {
        try {
            var group = userGroupService.getGroup(id, principal.organizationId());
            return toResource(principal, group, baseUrl, false);
        } catch (UserGroupNotFoundException ex) {
            throw new ScimResourceNotFoundException(RESOURCE_TYPE, id.toString());
        }
    }

    /** PUT — replaces displayName/externalId and the SCIM-sourced member set. */
    public ScimGroupResource replace(ScimPrincipal principal, UUID id, ScimGroupResource resource,
                              String baseUrl) {
        requireExists(principal, id);
        if (resource.displayName() == null || resource.displayName().isBlank()) {
            throw new ScimInvalidValueException("Missing required attribute: displayName");
        }
        var externalId = blankToNull(resource.externalId());
        if (externalId != null) {
            var conflicting = findByExternalId(principal, externalId);
            if (conflicting != null && !conflicting.id().equals(id)) {
                throw new ScimUniquenessException("A group with this externalId already exists");
            }
        }
        try {
            userGroupService.updateGroup(id, principal.organizationId(),
                    new UpdateUserGroupCommand(resource.displayName(), null, externalId));
        } catch (UserGroupNameAlreadyExistsException ex) {
            throw new ScimUniquenessException("A group with this displayName already exists");
        }
        if (resource.members() != null) {
            userGroupService.replaceMembersBySource(id, principal.organizationId(),
                    memberIds(resource.members()), UserGroupMembershipSourceType.SCIM);
        }
        return get(principal, id, baseUrl);
    }

    public ScimGroupResource patch(ScimPrincipal principal, UUID id, ScimPatchRequest patch,
                            String baseUrl) {
        requireExists(principal, id);
        if (patch == null || patch.operations() == null || patch.operations().isEmpty()) {
            throw new ScimInvalidValueException("PatchOp must carry at least one operation");
        }
        for (var operation : patch.operations()) {
            var op = operation.op() == null ? "" : operation.op().toLowerCase(Locale.ROOT);
            var path = operation.path() == null ? null
                    : operation.path().trim().toLowerCase(Locale.ROOT);
            var value = operation.value();

            if (path != null) {
                var memberMatcher = MEMBERS_VALUE_PATH.matcher(operation.path().trim());
                if (memberMatcher.matches()) {
                    if (!op.equals("remove")) {
                        throw new ScimInvalidPathException(
                                "Filtered members path supports only remove");
                    }
                    removeMember(principal, id, memberMatcher.group(1));
                    continue;
                }
            }

            switch (op) {
                case "add", "replace" -> applyAddOrReplace(principal, id, op, path, value);
                case "remove" -> applyRemove(principal, id, path, value);
                default -> throw new ScimInvalidPathException("Unsupported patch op: "
                        + operation.op());
            }
        }
        return get(principal, id, baseUrl);
    }

    /** @return the deleted group's view — the controller audits its name and member count. */
    public UserGroupView delete(ScimPrincipal principal, UUID id) {
        try {
            var group = userGroupService.getGroup(id, principal.organizationId());
            userGroupService.deleteGroup(id, principal.organizationId());
            return group;
        } catch (UserGroupNotFoundException ex) {
            throw new ScimResourceNotFoundException(RESOURCE_TYPE, id.toString());
        }
    }

    private void applyAddOrReplace(ScimPrincipal principal, UUID groupId, String op, String path,
                                   JsonNode value) {
        if (path == null) {
            if (value == null || !value.isObject()) {
                throw new ScimInvalidValueException("Patch value must be an object");
            }
            if (value.has("displayName")) {
                rename(principal, groupId, value.get("displayName").asString());
            }
            if (value.has("externalId")) {
                reExternalId(principal, groupId, value.get("externalId").asString());
            }
            if (value.has("members")) {
                replaceOrAddMembers(principal, groupId, op, value.get("members"));
            }
            return;
        }
        switch (path) {
            case "displayname" -> rename(principal, groupId, textOf(value));
            case "externalid" -> reExternalId(principal, groupId, textOf(value));
            case "members" -> replaceOrAddMembers(principal, groupId, op, value);
            default -> throw new ScimInvalidPathException("Unsupported patch path: " + path);
        }
    }

    private void applyRemove(ScimPrincipal principal, UUID groupId, String path, JsonNode value) {
        if (!"members".equals(path)) {
            throw new ScimInvalidPathException("remove supports only the members path");
        }
        if (value == null || value.isNull()) {
            // remove with path "members" and no value clears the SCIM-sourced member set.
            userGroupService.replaceMembersBySource(groupId, principal.organizationId(),
                    List.of(), UserGroupMembershipSourceType.SCIM);
            return;
        }
        for (var memberId : memberIdsFromNode(value)) {
            userGroupService.removeMemberBySource(groupId, memberId, principal.organizationId(),
                    UserGroupMembershipSourceType.SCIM);
        }
    }

    private void replaceOrAddMembers(ScimPrincipal principal, UUID groupId, String op,
                                     JsonNode value) {
        var memberIds = memberIdsFromNode(value);
        if ("replace".equals(op)) {
            userGroupService.replaceMembersBySource(groupId, principal.organizationId(), memberIds,
                    UserGroupMembershipSourceType.SCIM);
            return;
        }
        for (var memberId : memberIds) {
            try {
                userGroupService.addMember(groupId, memberId, principal.organizationId(),
                        UserGroupMembershipSourceType.SCIM);
            } catch (UserNotFoundException ex) {
                // Unknown member ids are skipped — the IdP may race a user delete.
            }
        }
    }

    private void removeMember(ScimPrincipal principal, UUID groupId, String rawMemberId) {
        userGroupService.removeMemberBySource(groupId, parseMemberId(rawMemberId),
                principal.organizationId(), UserGroupMembershipSourceType.SCIM);
    }

    private void rename(ScimPrincipal principal, UUID groupId, String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new ScimInvalidValueException("displayName must not be blank");
        }
        try {
            userGroupService.updateGroup(groupId, principal.organizationId(),
                    new UpdateUserGroupCommand(displayName, null, null));
        } catch (UserGroupNameAlreadyExistsException ex) {
            throw new ScimUniquenessException("A group with this displayName already exists");
        }
    }

    private void reExternalId(ScimPrincipal principal, UUID groupId, String externalId) {
        var normalized = blankToNull(externalId);
        if (normalized != null) {
            var conflicting = findByExternalId(principal, normalized);
            if (conflicting != null && !conflicting.id().equals(groupId)) {
                throw new ScimUniquenessException("A group with this externalId already exists");
            }
        }
        userGroupService.updateGroup(groupId, principal.organizationId(),
                new UpdateUserGroupCommand(null, null, normalized == null ? "" : normalized));
    }

    private UserGroupView findByExternalId(ScimPrincipal principal, String externalId) {
        return userGroupService.listAll(principal.organizationId()).stream()
                .filter(g -> externalId.equals(g.scimExternalId()))
                .findFirst()
                .orElse(null);
    }

    private void requireExists(ScimPrincipal principal, UUID id) {
        try {
            userGroupService.getGroup(id, principal.organizationId());
        } catch (UserGroupNotFoundException ex) {
            throw new ScimResourceNotFoundException(RESOURCE_TYPE, id.toString());
        }
    }

    private ScimGroupResource toResource(ScimPrincipal principal, UserGroupView group,
                                         String baseUrl, boolean omitMembers) {
        List<ScimMemberRef> members = null;
        if (!omitMembers) {
            members = userGroupService.listMembers(group.id(), principal.organizationId()).stream()
                    .map(m -> new ScimMemberRef(m.userId().toString(), m.userEmail()))
                    .toList();
        }
        return new ScimGroupResource(
                List.of(ScimSchemas.GROUP),
                group.id().toString(),
                group.scimExternalId(),
                group.name(),
                members,
                new ScimMeta(RESOURCE_TYPE, group.createdAt(), group.updatedAt(),
                        baseUrl + "/Groups/" + group.id()));
    }

    private static List<UUID> memberIds(List<ScimMemberRef> members) {
        var ids = new LinkedHashSet<UUID>();
        for (var member : members) {
            ids.add(parseMemberId(member.value()));
        }
        return new ArrayList<>(ids);
    }

    private static List<UUID> memberIdsFromNode(JsonNode value) {
        if (value == null || !value.isArray()) {
            throw new ScimInvalidValueException("members value must be an array");
        }
        var ids = new ArrayList<UUID>();
        for (JsonNode member : value) {
            var idNode = member.isObject() ? member.get("value") : member;
            ids.add(parseMemberId(idNode == null || idNode.isNull() ? null : idNode.asString()));
        }
        return ids;
    }

    private static UUID parseMemberId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ScimInvalidValueException("Member value must be a user id");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new ScimInvalidValueException("Member value is not a valid user id: " + raw);
        }
    }

    private static String textOf(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        return value.isString() ? value.asString() : value.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
