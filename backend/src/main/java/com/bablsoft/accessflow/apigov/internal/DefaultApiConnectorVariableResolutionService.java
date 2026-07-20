package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorVariableLookupService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorVariableResolutionService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorVariableSummaryView;
import com.bablsoft.accessflow.apigov.api.ApiExecutionException;
import com.bablsoft.accessflow.apigov.api.ApiVariableRequestContext;
import com.bablsoft.accessflow.apigov.api.ResolvedApiVariables;
import com.bablsoft.accessflow.apigov.internal.config.ApigovRequestProperties;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorVariableEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorVariableRepository;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Evaluates a connector's dynamic variables for one outbound call (AF-613).
 *
 * <p>Runs after auth headers are resolved, so an expression can sign the finished
 * {@code Authorization} value. Variables are evaluated in topological order; each expression is
 * rendered against the pre-substitution request context plus the variables already resolved in this
 * pass, and the result is fed to {@link ApiVariableEvaluator}.
 *
 * <p>Resolved values never leave this call: they are not persisted onto the request row, not written
 * into the response snapshot, and not logged. The resolver cannot distinguish a signature (harmless)
 * from a {@code CONSTANT} holding a shared secret (not harmless), so every resolved value is treated
 * as sensitive — see {@link ResolvedApiVariables#secretValues()}.
 */
@Service
@RequiredArgsConstructor
class DefaultApiConnectorVariableResolutionService
        implements ApiConnectorVariableResolutionService, ApiConnectorVariableLookupService {

    private final ApiConnectorVariableRepository variableRepository;
    private final CredentialEncryptionService encryptionService;
    private final ApiVariableEvaluator evaluator;
    private final ApigovRequestProperties requestProperties;
    private final MessageSource messageSource;

    @Override
    @Transactional(readOnly = true)
    public ResolvedApiVariables resolve(UUID organizationId, UUID connectorId,
                                        ApiVariableRequestContext context,
                                        Map<String, String> overrides) {
        var entities = variableRepository
                .findAllByOrganizationIdAndConnectorIdOrderBySortOrderAscCreatedAtAscIdAsc(
                        organizationId, connectorId);
        if (entities.isEmpty()) {
            return ResolvedApiVariables.empty();
        }

        var byName = entities.stream()
                .collect(Collectors.toMap(ApiConnectorVariableEntity::getName, e -> e, (a, b) -> a,
                        LinkedHashMap::new));
        var nodes = entities.stream()
                .map(e -> new ApiVariableGraph.Node(e.getName(), e.getExpression()))
                .toList();

        // Defensive: the admin service already rejected cycles and unknown references at save time.
        // Reaching either here means the rows were mutated outside it.
        List<ApiVariableGraph.Node> ordered;
        try {
            ordered = ApiVariableGraph.evaluationOrder(nodes);
        } catch (ApiVariableGraph.CycleException ex) {
            throw new ApiExecutionException(msg("error.api_connector_variable_cycle",
                    String.join(", ", ex.names())));
        } catch (ApiVariableGraph.UnknownReferenceException ex) {
            throw new ApiExecutionException(msg("error.api_connector_variable_unknown_reference",
                    ex.from(), ex.missing()));
        }

        var effectiveOverrides = overrides == null ? Map.<String, String>of() : overrides;
        var resolved = new LinkedHashMap<String, String>();
        for (var node : ordered) {
            var entity = byName.get(node.name());
            resolved.put(entity.getName(), valueFor(entity, context, resolved, effectiveOverrides));
        }

        return new ResolvedApiVariables(resolved, injections(entities, resolved));
    }

    private String valueFor(ApiConnectorVariableEntity entity, ApiVariableRequestContext context,
                            Map<String, String> resolved, Map<String, String> overrides) {
        var name = entity.getName();

        // An override replaces the value outright — the kind, algorithm and encoding are not
        // re-applied. It is inserted as an opaque literal and never rendered as a template, which is
        // what stops an override of "{{someSecret}}" from expanding into that secret's value.
        // Variables that depend on this one still recompute over the overridden value.
        if (entity.isOverridable() && overrides.containsKey(name)) {
            return validate(name, overrides.get(name));
        }

        String input;
        try {
            input = ApiVariableTemplate.render(entity.getExpression(),
                    ref -> switch (ref.scope()) {
                        case VARIABLE_BARE, VARIABLE_QUALIFIED -> resolved.get(ref.key());
                        case REQUEST -> requestValue(context, ref.key());
                        case FOREIGN -> null;
                    },
                    ApiVariableTemplate.EXPRESSION_STRICT_SCOPES);
        } catch (ApiVariableTemplate.UnresolvedReferenceException ex) {
            throw new ApiExecutionException(msg("error.api_connector_variable_unknown_reference",
                    name, ex.reference().raw()));
        }

        try {
            var secret = entity.getSecretEncrypted() == null
                    ? null : encryptionService.decrypt(entity.getSecretEncrypted());
            return validate(name, evaluator.evaluate(name, entity.getKind(), entity.getAlgorithm(),
                    entity.getEncoding(), input, secret));
        } catch (ApiVariableEvaluationException ex) {
            throw new ApiExecutionException(msg(ex.messageKey(), ex.args()));
        }
    }

    /** {@code {{request.headers.<Name>}}} is matched case-insensitively, as HTTP headers are. */
    private static String requestValue(ApiVariableRequestContext context, String key) {
        if (key.startsWith("headers.")) {
            var wanted = key.substring("headers.".length());
            var caseInsensitive = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
            caseInsensitive.putAll(context.headers());
            return caseInsensitive.get(wanted);
        }
        return switch (key) {
            case "method" -> context.method();
            case "path" -> context.path();
            case "query" -> context.query();
            case "body" -> context.body();
            default -> null;
        };
    }

    /**
     * No resolved value may carry CR, LF or NUL. Any of them landing in a header is request
     * splitting, and a submitter-supplied override is the natural delivery mechanism. The check is
     * unconditional rather than header-only because a value reaches a header just as easily through
     * a {@code {{name}}} placeholder as through a {@code header:} target. The JDK's own check throws
     * {@code IllegalArgumentException} deep inside the client, which would surface as a 500;
     * rejecting here yields a clean, localized failure instead.
     */
    private String validate(String name, String value) {
        var safe = value == null ? "" : value;
        if (safe.getBytes(StandardCharsets.UTF_8).length > requestProperties.maxVariableValueBytes()) {
            throw new ApiExecutionException(msg("error.api_connector_variable_value_too_large",
                    name, requestProperties.maxVariableValueBytes()));
        }
        if (containsControlCharacters(safe)) {
            throw new ApiExecutionException(msg("error.api_connector_variable_value_invalid", name));
        }
        return safe;
    }

    static boolean containsControlCharacters(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0;
    }

    private static List<ResolvedApiVariables.ApiVariableInjection> injections(
            List<ApiConnectorVariableEntity> entities, Map<String, String> resolved) {
        var injections = new ArrayList<ResolvedApiVariables.ApiVariableInjection>();
        for (var entity : entities) {
            var target = ApiVariableTargets.parse(entity.getTarget());
            if (target != null) {
                injections.add(new ResolvedApiVariables.ApiVariableInjection(
                        target.type(), target.key(), resolved.getOrDefault(entity.getName(), "")));
            }
        }
        return injections;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApiConnectorVariableSummaryView> summariesForConnector(UUID connectorId,
                                                                       UUID organizationId) {
        return variableRepository
                .findAllByOrganizationIdAndConnectorIdOrderBySortOrderAscCreatedAtAscIdAsc(
                        organizationId, connectorId)
                .stream()
                .map(e -> new ApiConnectorVariableSummaryView(e.getName(), e.getKind(),
                        e.getDescription(), e.isOverridable()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> overridableNames(UUID connectorId, UUID organizationId) {
        return variableRepository
                .findAllByOrganizationIdAndConnectorIdOrderBySortOrderAscCreatedAtAscIdAsc(
                        organizationId, connectorId)
                .stream()
                .filter(ApiConnectorVariableEntity::isOverridable)
                .map(ApiConnectorVariableEntity::getName)
                .collect(Collectors.toSet());
    }

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }
}
