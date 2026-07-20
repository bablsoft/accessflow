package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiConnectorVariableAdminService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorVariableNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiConnectorVariableView;
import com.bablsoft.accessflow.apigov.api.ApiVariableAlgorithm;
import com.bablsoft.accessflow.apigov.api.ApiVariableKind;
import com.bablsoft.accessflow.apigov.api.CreateApiConnectorVariableCommand;
import com.bablsoft.accessflow.apigov.api.IllegalApiConnectorVariableException;
import com.bablsoft.accessflow.apigov.api.ReorderApiConnectorVariablesCommand;
import com.bablsoft.accessflow.apigov.api.UpdateApiConnectorVariableCommand;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorVariableEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorVariableRepository;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Admin CRUD for connector dynamic variables (AF-613).
 *
 * <p>Validation is authoritative here rather than at execution time. Every mutation re-materializes
 * the connector's whole variable set with the candidate applied and re-runs the dependency sort, so
 * a cycle or a dangling reference is a 422 the operator sees while editing — not a failed call hours
 * later, after a reviewer has already approved the request.
 */
@Service
@RequiredArgsConstructor
class DefaultApiConnectorVariableAdminService implements ApiConnectorVariableAdminService {

    private static final int MAX_VARIABLES_PER_CONNECTOR = 64;

    private final ApiConnectorVariableRepository variableRepository;
    private final ApiConnectorRepository connectorRepository;
    private final CredentialEncryptionService encryptionService;
    private final MessageSource messageSource;

    @Override
    @Transactional(readOnly = true)
    public List<ApiConnectorVariableView> listForConnector(UUID connectorId, UUID organizationId) {
        requireConnectorInOrganization(connectorId, organizationId);
        return load(connectorId, organizationId).stream().map(this::toView).toList();
    }

    @Override
    @Transactional
    public ApiConnectorVariableView create(UUID connectorId, UUID organizationId,
                                           CreateApiConnectorVariableCommand command) {
        requireConnectorInOrganization(connectorId, organizationId);
        var existing = load(connectorId, organizationId);
        if (existing.size() >= MAX_VARIABLES_PER_CONNECTOR) {
            throw illegal("error.api_connector_variable_too_many", MAX_VARIABLES_PER_CONNECTOR);
        }

        var entity = new ApiConnectorVariableEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setConnectorId(connectorId);
        entity.setName(requireName(command.name()));
        entity.setKind(requireKind(command.kind()));
        entity.setExpression(blankToNull(command.expression()));
        entity.setAlgorithm(command.algorithm());
        entity.setEncoding(command.encoding());
        entity.setSecretEncrypted(encryptSecret(command.secret()));
        entity.setTarget(ApiVariableTargets.normalize(command.target()));
        entity.setOverridable(Boolean.TRUE.equals(command.overridable()));
        entity.setDescription(blankToNull(command.description()));
        entity.setSortOrder(command.sortOrder() == null ? nextSortOrder(existing) : command.sortOrder());

        validate(entity, existing);
        return toView(variableRepository.save(entity));
    }

    @Override
    @Transactional
    public ApiConnectorVariableView update(UUID variableId, UUID connectorId, UUID organizationId,
                                           UpdateApiConnectorVariableCommand command) {
        requireConnectorInOrganization(connectorId, organizationId);
        var entity = loadInScope(variableId, connectorId, organizationId);
        var previousName = entity.getName();

        entity.setName(requireName(command.name()));
        entity.setKind(requireKind(command.kind()));
        entity.setExpression(blankToNull(command.expression()));
        entity.setAlgorithm(command.algorithm());
        entity.setEncoding(command.encoding());
        entity.setTarget(ApiVariableTargets.normalize(command.target()));
        entity.setOverridable(Boolean.TRUE.equals(command.overridable()));
        entity.setDescription(blankToNull(command.description()));
        if (command.sortOrder() != null) {
            entity.setSortOrder(command.sortOrder());
        }
        // The stored secret is never sent to the client, so an unchanged field cannot be
        // round-tripped: null means "leave it", an explicit clear removes it.
        if (Boolean.TRUE.equals(command.clearSecret())) {
            entity.setSecretEncrypted(null);
        } else if (command.secret() != null && !command.secret().isBlank()) {
            entity.setSecretEncrypted(encryptSecret(command.secret()));
        }

        var others = load(connectorId, organizationId).stream()
                .filter(e -> !e.getId().equals(variableId))
                .toList();
        // A rename orphans every reference to the old name, so it is validated like a delete.
        if (!previousName.equals(entity.getName())) {
            requireNotReferenced(previousName, others);
        }
        validate(entity, others);
        return toView(variableRepository.save(entity));
    }

    @Override
    @Transactional
    public void delete(UUID variableId, UUID connectorId, UUID organizationId) {
        requireConnectorInOrganization(connectorId, organizationId);
        var entity = loadInScope(variableId, connectorId, organizationId);
        var survivors = load(connectorId, organizationId).stream()
                .filter(e -> !e.getId().equals(variableId))
                .toList();
        requireNotReferenced(entity.getName(), survivors);
        variableRepository.delete(entity);
    }

    @Override
    @Transactional
    public List<ApiConnectorVariableView> reorder(UUID connectorId, UUID organizationId,
                                                   ReorderApiConnectorVariablesCommand command) {
        requireConnectorInOrganization(connectorId, organizationId);
        var existing = load(connectorId, organizationId);
        var requested = command.variableIds();
        // The list must be the complete set: a partial reorder would leave the rest at stale
        // positions, which is exactly the kind of silent surprise evaluation order must not have.
        if (requested.size() != existing.size()
                || !new HashSet<>(requested).equals(existing.stream()
                        .map(ApiConnectorVariableEntity::getId).collect(Collectors.toSet()))) {
            throw illegal("error.api_connector_variable_reorder_incomplete");
        }
        for (var i = 0; i < requested.size(); i++) {
            var id = requested.get(i);
            var entity = existing.stream().filter(e -> e.getId().equals(id)).findFirst().orElseThrow();
            entity.setSortOrder(i);
            variableRepository.save(entity);
        }
        return load(connectorId, organizationId).stream().map(this::toView).toList();
    }

    // --- validation ---------------------------------------------------------------------------

    /** Validates {@code candidate} both on its own and against the rest of the connector's set. */
    private void validate(ApiConnectorVariableEntity candidate, List<ApiConnectorVariableEntity> others) {
        validateKindFields(candidate);
        validateOverridable(candidate);
        validateTarget(candidate, others);

        for (var other : others) {
            if (other.getName().equals(candidate.getName())) {
                throw illegal("error.api_connector_variable_name_duplicate", candidate.getName());
            }
        }

        var combined = new ArrayList<>(others);
        combined.add(candidate);
        combined.sort(Comparator.comparingInt(ApiConnectorVariableEntity::getSortOrder)
                .thenComparing(ApiConnectorVariableEntity::getName));
        var nodes = combined.stream()
                .map(e -> new ApiVariableGraph.Node(e.getName(), e.getExpression()))
                .toList();
        try {
            ApiVariableGraph.evaluationOrder(nodes);
        } catch (ApiVariableGraph.CycleException ex) {
            throw illegal("error.api_connector_variable_cycle", String.join(", ", ex.names()));
        } catch (ApiVariableGraph.UnknownReferenceException ex) {
            throw illegal("error.api_connector_variable_unknown_reference", ex.from(), ex.missing());
        }
    }

    private void validateKindFields(ApiConnectorVariableEntity e) {
        var name = e.getName();
        var hasExpression = e.getExpression() != null && !e.getExpression().isBlank();

        switch (e.getKind()) {
            case UUID, EPOCH_MILLIS -> {
                if (hasExpression) {
                    throw illegal("error.api_connector_variable_expression_forbidden", name);
                }
            }
            case CONSTANT, HASH, HMAC, ENCODE -> {
                if (!hasExpression) {
                    throw illegal("error.api_connector_variable_expression_required", name);
                }
            }
            case TIMESTAMP, RANDOM_HEX -> {
                // Expression is optional: a format pattern / a byte count.
            }
        }

        var allowedAlgorithms = switch (e.getKind()) {
            case HASH -> Set.of(ApiVariableAlgorithm.SHA256, ApiVariableAlgorithm.MD5);
            case HMAC -> Set.of(ApiVariableAlgorithm.HMAC_SHA256, ApiVariableAlgorithm.HMAC_SHA512);
            default -> Set.<ApiVariableAlgorithm>of();
        };
        if (allowedAlgorithms.isEmpty()) {
            if (e.getAlgorithm() != null) {
                throw illegal("error.api_connector_variable_algorithm_forbidden", name);
            }
        } else if (e.getAlgorithm() == null || !allowedAlgorithms.contains(e.getAlgorithm())) {
            throw illegal("error.api_connector_variable_algorithm_invalid", name);
        }

        switch (e.getKind()) {
            case ENCODE -> {
                if (e.getEncoding() == null) {
                    throw illegal("error.api_connector_variable_encoding_required", name);
                }
            }
            // CONSTANT deliberately ignores encoding rather than re-encoding — that is what keeps it
            // distinct from ENCODE. Rejecting an encoding here avoids a silently ineffective setting.
            case CONSTANT, UUID, TIMESTAMP, EPOCH_MILLIS -> {
                if (e.getEncoding() != null) {
                    throw illegal("error.api_connector_variable_encoding_forbidden", name);
                }
            }
            case RANDOM_HEX, HASH, HMAC -> {
                // Optional; defaults to HEX.
            }
        }

        var hasSecret = e.getSecretEncrypted() != null;
        if (e.getKind() == ApiVariableKind.HMAC) {
            if (!hasSecret) {
                throw illegal("error.api_connector_variable_secret_required", name);
            }
        } else if (hasSecret) {
            throw illegal("error.api_connector_variable_secret_forbidden", name);
        }

        if (e.getKind() == ApiVariableKind.RANDOM_HEX && hasExpression) {
            try {
                var count = Integer.parseInt(e.getExpression().trim());
                if (count < 1 || count > 256) {
                    throw illegal("error.api_connector_variable_random_hex_size", name, 1, 256);
                }
            } catch (NumberFormatException ex) {
                throw illegal("error.api_connector_variable_random_hex_size", name, 1, 256);
            }
        }
    }

    /**
     * A submitter must never be able to override a value that <em>is</em> a secret. The database
     * enforces the same rule with a CHECK constraint, so it survives a manual data fix.
     */
    private void validateOverridable(ApiConnectorVariableEntity e) {
        if (e.isOverridable()
                && (e.getSecretEncrypted() != null || e.getKind() == ApiVariableKind.HMAC)) {
            throw illegal("error.api_connector_variable_overridable_secret", e.getName());
        }
    }

    private void validateTarget(ApiConnectorVariableEntity candidate,
                                List<ApiConnectorVariableEntity> others) {
        if (!ApiVariableTargets.isValid(candidate.getTarget())) {
            throw illegal("error.api_connector_variable_target_invalid", candidate.getName());
        }
        var target = ApiVariableTargets.parse(candidate.getTarget());
        if (target == null) {
            return;
        }
        for (var other : others) {
            var otherTarget = ApiVariableTargets.parse(other.getTarget());
            if (otherTarget != null && otherTarget.type() == target.type()
                    && otherTarget.key().equalsIgnoreCase(target.key())) {
                throw illegal("error.api_connector_variable_target_duplicate", candidate.getTarget());
            }
        }
    }

    private void requireNotReferenced(String name, List<ApiConnectorVariableEntity> others) {
        for (var other : others) {
            if (ApiVariableTemplate.variableReferences(other.getExpression()).contains(name)) {
                throw illegal("error.api_connector_variable_referenced", name, other.getName());
            }
        }
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw illegal("error.api_connector_variable_name_required");
        }
        var trimmed = name.trim();
        if (!ApiVariableTemplate.NAME_PATTERN.matcher(trimmed).matches()) {
            throw illegal("error.api_connector_variable_name_invalid", trimmed);
        }
        return trimmed;
    }

    private ApiVariableKind requireKind(ApiVariableKind kind) {
        if (kind == null) {
            throw illegal("error.api_connector_variable_kind_required");
        }
        return kind;
    }

    // --- helpers ------------------------------------------------------------------------------

    private List<ApiConnectorVariableEntity> load(UUID connectorId, UUID organizationId) {
        return variableRepository
                .findAllByOrganizationIdAndConnectorIdOrderBySortOrderAscCreatedAtAscIdAsc(
                        organizationId, connectorId);
    }

    private ApiConnectorVariableEntity loadInScope(UUID variableId, UUID connectorId,
                                                   UUID organizationId) {
        return variableRepository
                .findByIdAndOrganizationIdAndConnectorId(variableId, organizationId, connectorId)
                .orElseThrow(() -> new ApiConnectorVariableNotFoundException(variableId));
    }

    private void requireConnectorInOrganization(UUID connectorId, UUID organizationId) {
        connectorRepository.findByIdAndOrganizationId(connectorId, organizationId)
                .orElseThrow(() -> new ApiConnectorNotFoundException(connectorId));
    }

    private static int nextSortOrder(List<ApiConnectorVariableEntity> existing) {
        return existing.stream().mapToInt(ApiConnectorVariableEntity::getSortOrder).max().orElse(-1) + 1;
    }

    private String encryptSecret(String raw) {
        return raw == null || raw.isBlank() ? null : encryptionService.encrypt(raw);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private ApiConnectorVariableView toView(ApiConnectorVariableEntity e) {
        return new ApiConnectorVariableView(e.getId(), e.getConnectorId(), e.getName(), e.getKind(),
                e.getExpression(), e.getAlgorithm(), e.getEncoding(), e.getSecretEncrypted() != null,
                e.getTarget(), e.isOverridable(), e.getDescription(), e.getSortOrder(),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    private IllegalApiConnectorVariableException illegal(String key, Object... args) {
        return new IllegalApiConnectorVariableException(
                messageSource.getMessage(key, args, LocaleContextHolder.getLocale()));
    }
}
