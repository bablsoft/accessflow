package com.bablsoft.accessflow.scim.internal;

import com.bablsoft.accessflow.core.api.CreateExternalUserCommand;
import com.bablsoft.accessflow.core.api.EmailAlreadyExistsException;
import com.bablsoft.accessflow.core.api.ExternalIdAlreadyExistsException;
import com.bablsoft.accessflow.core.api.ExternalUserDirectoryService;
import com.bablsoft.accessflow.core.api.UpdateExternalUserCommand;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.scim.api.ScimConfigService;
import com.bablsoft.accessflow.scim.api.ScimConfigView;
import com.bablsoft.accessflow.scim.api.ScimPrincipal;
import com.bablsoft.accessflow.scim.internal.protocol.ScimEmail;
import com.bablsoft.accessflow.scim.internal.protocol.ScimFilter;
import com.bablsoft.accessflow.scim.internal.protocol.ScimFilterParser;
import com.bablsoft.accessflow.scim.internal.protocol.ScimInvalidFilterException;
import com.bablsoft.accessflow.scim.internal.protocol.ScimInvalidPathException;
import com.bablsoft.accessflow.scim.internal.protocol.ScimInvalidValueException;
import com.bablsoft.accessflow.scim.internal.protocol.ScimListResponse;
import com.bablsoft.accessflow.scim.internal.protocol.ScimMeta;
import com.bablsoft.accessflow.scim.internal.protocol.ScimName;
import com.bablsoft.accessflow.scim.internal.protocol.ScimPatchOperation;
import com.bablsoft.accessflow.scim.internal.protocol.ScimPatchRequest;
import com.bablsoft.accessflow.scim.internal.protocol.ScimResourceNotFoundException;
import com.bablsoft.accessflow.scim.internal.protocol.ScimSchemas;
import com.bablsoft.accessflow.scim.internal.protocol.ScimUniquenessException;
import com.bablsoft.accessflow.scim.internal.protocol.ScimUserResource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Maps the SCIM User wire contract onto {@link ExternalUserDirectoryService} (#621): attribute
 * mapping resolution, filter dispatch, PatchOp application, and the SCIM-owned-attributes-only
 * write boundary. Everything org-scoped through the authenticated {@link ScimPrincipal}.
 */
@Service
@RequiredArgsConstructor
public class ScimUserOrchestrator {

    static final String RESOURCE_TYPE = "User";

    private final ExternalUserDirectoryService directory;
    private final ScimConfigService configService;

    public ScimListResponse<ScimUserResource> list(ScimPrincipal principal, String filterExpression,
                                            int startIndex, int count, String baseUrl) {
        var config = configService.get(principal.organizationId());
        var normalizedStart = Math.max(1, startIndex);
        var normalizedCount = Math.clamp(count, 1, 200);
        var filter = ScimFilterParser.parse(filterExpression);
        if (filter == null) {
            var page = directory.list(principal.organizationId(), normalizedStart - 1,
                    normalizedCount);
            return ScimListResponse.of(page.totalResults(), normalizedStart,
                    page.content().stream().map(u -> toResource(u, config, baseUrl)).toList());
        }
        var match = findByFilter(principal.organizationId(), filter);
        return ScimListResponse.of(match == null ? 0 : 1, 1,
                match == null ? List.of() : List.of(toResource(match, config, baseUrl)));
    }

    @Transactional
    public ScimUserResource create(ScimPrincipal principal, ScimUserResource resource, String baseUrl) {
        var config = configService.get(principal.organizationId());
        var email = extractEmail(resource, config);
        if (email == null || email.isBlank()) {
            throw new ScimInvalidValueException(
                    "Missing required email source attribute: " + config.attrEmail());
        }
        try {
            var created = directory.createExternal(new CreateExternalUserCommand(
                    principal.organizationId(),
                    email,
                    extractDisplayName(resource, config),
                    blankToNull(resource.externalId()),
                    config.defaultRole()));
            return toResource(created, config, baseUrl);
        } catch (EmailAlreadyExistsException ex) {
            throw new ScimUniquenessException("A user with this email already exists");
        } catch (ExternalIdAlreadyExistsException ex) {
            throw new ScimUniquenessException("A user with this externalId already exists");
        }
    }

    public ScimUserResource get(ScimPrincipal principal, UUID id, String baseUrl) {
        var config = configService.get(principal.organizationId());
        return directory.findById(principal.organizationId(), id)
                .map(u -> toResource(u, config, baseUrl))
                .orElseThrow(() -> new ScimResourceNotFoundException(RESOURCE_TYPE, id.toString()));
    }

    /** PUT — full replace of the SCIM-owned attributes only. */
    @Transactional
    public ScimUserWriteResult replace(ScimPrincipal principal, UUID id, ScimUserResource resource,
                                String baseUrl) {
        var config = configService.get(principal.organizationId());
        var existing = loadOrThrow(principal, id);
        var email = extractEmail(resource, config);
        if (email == null || email.isBlank()) {
            throw new ScimInvalidValueException(
                    "Missing required email source attribute: " + config.attrEmail());
        }
        var command = new UpdateExternalUserCommand(
                email,
                extractDisplayName(resource, config),
                blankToNull(resource.externalId()),
                resource.active());
        return apply(principal, existing, command, config, baseUrl);
    }

    @Transactional
    public ScimUserWriteResult patch(ScimPrincipal principal, UUID id, ScimPatchRequest patch,
                              String baseUrl) {
        var config = configService.get(principal.organizationId());
        var existing = loadOrThrow(principal, id);

        String email = null;
        String displayName = null;
        String externalId = null;
        Boolean active = null;
        for (var operation : operationsOf(patch)) {
            var op = operation.op() == null ? "" : operation.op().toLowerCase(Locale.ROOT);
            if (!op.equals("add") && !op.equals("replace")) {
                throw new ScimInvalidPathException("Unsupported patch op: " + operation.op());
            }
            var path = normalizePath(operation.path());
            var value = operation.value();
            if (path == null) {
                // No path: the value is an object of attribute -> new value.
                if (value == null || !value.isObject()) {
                    throw new ScimInvalidValueException("Patch value must be an object");
                }
                var it = value.properties().iterator();
                while (it.hasNext()) {
                    var entry = it.next();
                    switch (entry.getKey().toLowerCase(Locale.ROOT)) {
                        case "active" -> active = parseBoolean(entry.getValue());
                        case "displayname" -> displayName = textOf(entry.getValue());
                        case "externalid" -> externalId = textOf(entry.getValue());
                        case "username" -> email = emailFromUserName(entry.getValue(), config);
                        default -> {
                            // Unknown attributes (password, name.*, emails rewrites, enterprise
                            // extension fields) are ignored — SCIM owns a narrow attribute set.
                        }
                    }
                }
                continue;
            }
            switch (path) {
                case "active" -> active = parseBoolean(value);
                case "displayname", "name.formatted" -> displayName = textOf(value);
                case "externalid" -> externalId = textOf(value);
                case "username" -> email = emailFromUserName(value, config);
                default -> {
                    if (path.startsWith("emails")) {
                        email = "emails.primary".equals(config.attrEmail()) ? textOf(value) : email;
                    }
                    // Other paths are outside the SCIM-owned attribute set — ignored.
                }
            }
        }
        var command = new UpdateExternalUserCommand(email, displayName, externalId, active);
        return apply(principal, existing, command, config, baseUrl);
    }

    /** DELETE — AccessFlow never hard-deletes users; this deactivates (idempotently). */
    @Transactional
    public boolean delete(ScimPrincipal principal, UUID id) {
        var existing = loadOrThrow(principal, id);
        if (!existing.active()) {
            return false;
        }
        directory.updateExternal(principal.organizationId(), id,
                new UpdateExternalUserCommand(null, null, null, false));
        return true;
    }

    private ScimUserWriteResult apply(ScimPrincipal principal, UserView existing,
                                      UpdateExternalUserCommand command, ScimConfigView config,
                                      String baseUrl) {
        try {
            var updated = directory.updateExternal(principal.organizationId(), existing.id(),
                    command);
            var deactivated = existing.active() && !updated.active();
            return new ScimUserWriteResult(toResource(updated, config, baseUrl), deactivated);
        } catch (EmailAlreadyExistsException ex) {
            throw new ScimUniquenessException("A user with this email already exists");
        } catch (ExternalIdAlreadyExistsException ex) {
            throw new ScimUniquenessException("A user with this externalId already exists");
        }
    }

    private UserView loadOrThrow(ScimPrincipal principal, UUID id) {
        return directory.findById(principal.organizationId(), id)
                .orElseThrow(() -> new ScimResourceNotFoundException(RESOURCE_TYPE, id.toString()));
    }

    private UserView findByFilter(UUID organizationId, ScimFilter filter) {
        return switch (filter.attribute()) {
            case "username", "emails", "emails.value" ->
                    directory.findByEmail(organizationId, filter.value()).orElse(null);
            case "externalid" ->
                    directory.findByExternalId(organizationId, filter.value()).orElse(null);
            case "id" -> {
                try {
                    yield directory.findById(organizationId, UUID.fromString(filter.value()))
                            .orElse(null);
                } catch (IllegalArgumentException ex) {
                    yield null;
                }
            }
            default -> throw new ScimInvalidFilterException(
                    "Unsupported filter attribute: " + filter.attribute());
        };
    }

    private String extractEmail(ScimUserResource resource, ScimConfigView config) {
        if ("emails.primary".equals(config.attrEmail())) {
            var emails = resource.emails();
            if (emails == null || emails.isEmpty()) {
                return null;
            }
            return emails.stream()
                    .filter(e -> Boolean.TRUE.equals(e.primary()))
                    .map(ScimEmail::value)
                    .findFirst()
                    .orElse(emails.get(0).value());
        }
        return resource.userName();
    }

    private String extractDisplayName(ScimUserResource resource, ScimConfigView config) {
        var mapped = switch (config.attrDisplayName()) {
            case "name.formatted" -> resource.name() == null ? null : resource.name().formatted();
            case "userName" -> resource.userName();
            default -> resource.displayName();
        };
        if (mapped != null && !mapped.isBlank()) {
            return mapped;
        }
        if (resource.displayName() != null && !resource.displayName().isBlank()) {
            return resource.displayName();
        }
        if (resource.name() != null && resource.name().formatted() != null
                && !resource.name().formatted().isBlank()) {
            return resource.name().formatted();
        }
        return blankToNull(resource.userName());
    }

    private String emailFromUserName(JsonNode value, ScimConfigView config) {
        // userName is the email source unless the mapping reads emails.primary.
        return "userName".equals(config.attrEmail()) ? textOf(value) : null;
    }

    static ScimUserResource toResource(UserView user, ScimConfigView config, String baseUrl) {
        return new ScimUserResource(
                List.of(ScimSchemas.USER),
                user.id().toString(),
                user.scimExternalId(),
                user.email(),
                user.displayName(),
                user.displayName() == null ? null : new ScimName(user.displayName(), null, null),
                List.of(new ScimEmail(user.email(), "work", true)),
                user.active(),
                new ScimMeta(RESOURCE_TYPE, user.createdAt(),
                        user.updatedAt() != null ? user.updatedAt() : user.createdAt(),
                        baseUrl + "/Users/" + user.id()));
    }

    private static List<ScimPatchOperation> operationsOf(ScimPatchRequest patch) {
        if (patch == null || patch.operations() == null || patch.operations().isEmpty()) {
            throw new ScimInvalidValueException("PatchOp must carry at least one operation");
        }
        return patch.operations();
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        var lastColon = path.lastIndexOf(':');
        var stripped = lastColon >= 0 ? path.substring(lastColon + 1) : path;
        return stripped.toLowerCase(Locale.ROOT);
    }

    /** Entra sends booleans as strings ("True"/"False"); Okta sends real booleans. */
    static Boolean parseBoolean(JsonNode value) {
        if (value == null || value.isNull()) {
            throw new ScimInvalidValueException("Missing boolean value");
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        if (value.isString()) {
            var text = value.asString().trim().toLowerCase(Locale.ROOT);
            if (text.equals("true")) {
                return true;
            }
            if (text.equals("false")) {
                return false;
            }
        }
        throw new ScimInvalidValueException("Expected a boolean value");
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
