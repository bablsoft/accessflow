package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.AcceptedApiSchemaRevision;
import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiSchemaDriftFindingNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaDriftFindingView;
import com.bablsoft.accessflow.apigov.api.ApiSchemaDriftKind;
import com.bablsoft.accessflow.apigov.api.ApiSchemaDriftStatus;
import com.bablsoft.accessflow.apigov.api.ApiSchemaFetchException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaNotRefreshableException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaParseException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaRefreshResult;
import com.bablsoft.accessflow.apigov.api.ApiSchemaRevisionAlreadyAcceptedException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaRevisionNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaRevisionService;
import com.bablsoft.accessflow.apigov.api.ApiSchemaRevisionView;
import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import com.bablsoft.accessflow.apigov.api.ApiSchemaView;
import com.bablsoft.accessflow.apigov.api.ParsedApiSchema;
import com.bablsoft.accessflow.apigov.events.ApiSchemaDriftDetectedEvent;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiSchemaDriftFindingEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiSchemaEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiSchemaRevisionEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorGroupPermissionRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorUserPermissionRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiSchemaDriftFindingRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiSchemaRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiSchemaRevisionRepository;
import com.bablsoft.accessflow.apigov.internal.schema.OperationCatalogDiff;
import com.bablsoft.accessflow.apigov.internal.schema.OperationDiffFinding;
import com.bablsoft.accessflow.apigov.internal.schema.OperationFilterMatcher;
import com.bablsoft.accessflow.apigov.internal.schema.SchemaParserRegistry;
import com.bablsoft.accessflow.apigov.internal.schema.SchemaSourceFetcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Drift detection for {@code sourceUrl} schemas (#893). A refresh fetches and parses outside any
 * database transaction (the upstream may take its full 15 s timeout), then persists in a short one.
 * The governed catalog on {@code api_schemas} is only ever rewritten by {@link #accept}.
 */
@Service
@Slf4j
public class DefaultApiSchemaRevisionService implements ApiSchemaRevisionService {

    static final int MAX_ERROR_LENGTH = 1024;

    private final ApiConnectorRepository connectorRepository;
    private final ApiSchemaRepository schemaRepository;
    private final ApiSchemaRevisionRepository revisionRepository;
    private final ApiSchemaDriftFindingRepository findingRepository;
    private final ApiConnectorUserPermissionRepository userPermissionRepository;
    private final ApiConnectorGroupPermissionRepository groupPermissionRepository;
    private final SchemaSourceFetcher sourceFetcher;
    private final SchemaParserRegistry parserRegistry;
    private final OperationFilterMatcher filterMatcher;
    private final OperationCatalogDiff catalogDiff;
    private final ApiSchemaCatalogCodec codec;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    @SuppressWarnings("java:S107") // Collaborators only; grouping them would just hide the dependencies.
    public DefaultApiSchemaRevisionService(ApiConnectorRepository connectorRepository,
                                           ApiSchemaRepository schemaRepository,
                                           ApiSchemaRevisionRepository revisionRepository,
                                           ApiSchemaDriftFindingRepository findingRepository,
                                           ApiConnectorUserPermissionRepository userPermissionRepository,
                                           ApiConnectorGroupPermissionRepository groupPermissionRepository,
                                           SchemaSourceFetcher sourceFetcher,
                                           SchemaParserRegistry parserRegistry,
                                           OperationFilterMatcher filterMatcher,
                                           OperationCatalogDiff catalogDiff,
                                           ApiSchemaCatalogCodec codec,
                                           ObjectMapper objectMapper,
                                           ApplicationEventPublisher eventPublisher,
                                           Clock clock,
                                           PlatformTransactionManager transactionManager) {
        this.connectorRepository = connectorRepository;
        this.schemaRepository = schemaRepository;
        this.revisionRepository = revisionRepository;
        this.findingRepository = findingRepository;
        this.userPermissionRepository = userPermissionRepository;
        this.groupPermissionRepository = groupPermissionRepository;
        this.sourceFetcher = sourceFetcher;
        this.parserRegistry = parserRegistry;
        this.filterMatcher = filterMatcher;
        this.catalogDiff = catalogDiff;
        this.codec = codec;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public ApiSchemaRefreshResult refresh(UUID connectorId, UUID organizationId, UUID schemaId) {
        var source = transactionTemplate.execute(status -> {
            var connector = connectorRepository.findByIdAndOrganizationId(connectorId, organizationId)
                    .orElseThrow(() -> new ApiConnectorNotFoundException(connectorId));
            var schema = requireSchema(connectorId, schemaId);
            if (isBlank(schema.getSourceUrl())) {
                throw new ApiSchemaNotRefreshableException(schemaId);
            }
            return new RefreshSource(schema.getSchemaType(), schema.getSourceUrl(), connector.getOrganizationId());
        });
        String content;
        ParsedApiSchema parsed;
        try {
            content = sourceFetcher.fetch(source.sourceUrl());
            parsed = parserRegistry.parse(source.schemaType(), content);
        } catch (ApiSchemaFetchException | ApiSchemaParseException ex) {
            // Fail-safe: an unreachable or unparseable upstream is recorded, never reported as drift.
            recordFailure(schemaId, ex.getMessage());
            throw ex;
        }
        var stored = parsed.sanitizedContent() != null ? parsed.sanitizedContent() : content;
        return transactionTemplate.execute(status ->
                persist(connectorId, source.organizationId(), schemaId, parsed.operations(), stored));
    }

    private ApiSchemaRefreshResult persist(UUID connectorId, UUID organizationId, UUID schemaId,
                                           List<ApiOperation> candidate, String rawContent) {
        var schema = requireSchema(connectorId, schemaId);
        var now = clock.instant();
        schema.setLastRefreshAt(now);
        schema.setLastRefreshError(null);
        var hash = catalogDiff.contentHash(candidate);
        var existing = revisionRepository.findBySchemaIdAndContentHash(schemaId, hash);
        if (existing.isPresent()) {
            // Seen before (the newest revision, an older one, or the accepted catalog): no new row, and
            // the unique (schema_id, content_hash) constraint would refuse one anyway.
            existing.get().setLastSeenAt(now);
            return ApiSchemaRefreshResult.unchangedResult();
        }
        var current = codec.readOperations(schema.getParsedOperations());
        if (hash.equals(catalogDiff.contentHash(current))) {
            return ApiSchemaRefreshResult.unchangedResult();
        }
        var revision = new ApiSchemaRevisionEntity();
        revision.setId(UUID.randomUUID());
        revision.setSchemaId(schemaId);
        revision.setConnectorId(connectorId);
        revision.setContentHash(hash);
        revision.setRawContent(rawContent);
        revision.setParsedOperations(codec.writeOperations(candidate));
        revision.setOperationCount(candidate.size());
        revision.setFetchedAt(now);
        revision.setLastSeenAt(now);
        revisionRepository.save(revision);

        var filter = codec.readFilter(schema);
        var findings = catalogDiff.diff(filterMatcher.apply(current, filter), filterMatcher.apply(candidate, filter));
        for (var finding : findings) {
            findingRepository.save(toFindingEntity(finding, organizationId, connectorId, schemaId,
                    revision.getId(), now));
        }
        if (!findings.isEmpty()) {
            eventPublisher.publishEvent(new ApiSchemaDriftDetectedEvent(schemaId, connectorId, organizationId,
                    revision.getId(), findings.size()));
        }
        return new ApiSchemaRefreshResult(revision.getId(), findings.size(), false);
    }

    private ApiSchemaDriftFindingEntity toFindingEntity(OperationDiffFinding finding, UUID organizationId,
                                                        UUID connectorId, UUID schemaId, UUID revisionId,
                                                        Instant now) {
        var detail = objectMapper.createObjectNode();
        detail.set("before", objectMapper.valueToTree(finding.before()));
        detail.set("after", objectMapper.valueToTree(finding.after()));
        var affectedGrants = 0;
        if (finding.kind() == ApiSchemaDriftKind.OPERATION_REMOVED) {
            // Read-only: the grants are reported for the admin to decide on, never modified.
            var users = userPermissionRepository.findIdsByConnectorIdAndAllowedOperation(
                    connectorId, finding.operationId());
            var groups = groupPermissionRepository.findIdsByConnectorIdAndAllowedOperation(
                    connectorId, finding.operationId());
            var grants = detail.putObject("affected_grants");
            var userArray = grants.putArray("users");
            users.forEach(id -> userArray.add(id.toString()));
            var groupArray = grants.putArray("groups");
            groups.forEach(id -> groupArray.add(id.toString()));
            affectedGrants = users.size() + groups.size();
        }
        var entity = new ApiSchemaDriftFindingEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setConnectorId(connectorId);
        entity.setSchemaId(schemaId);
        entity.setRevisionId(revisionId);
        entity.setKind(finding.kind());
        entity.setOperationId(finding.operationId());
        entity.setDetail(objectMapper.writeValueAsString(detail));
        entity.setAffectedGrantCount(affectedGrants);
        entity.setStatus(ApiSchemaDriftStatus.OPEN);
        entity.setCreatedAt(now);
        return entity;
    }

    private void recordFailure(UUID schemaId, String message) {
        var now = clock.instant();
        var error = message == null ? "Schema refresh failed" : message;
        var truncated = error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
        transactionTemplate.executeWithoutResult(status -> schemaRepository.findById(schemaId).ifPresent(schema -> {
            schema.setLastRefreshAt(now);
            schema.setLastRefreshError(truncated);
        }));
    }

    @Override
    @Transactional
    public ApiSchemaView updateRefreshPolicy(UUID connectorId, UUID organizationId, UUID schemaId,
                                             Integer refreshIntervalMinutes) {
        requireConnector(connectorId, organizationId);
        var schema = requireSchema(connectorId, schemaId);
        if (isBlank(schema.getSourceUrl())) {
            throw new ApiSchemaNotRefreshableException(schemaId);
        }
        schema.setRefreshIntervalMinutes(refreshIntervalMinutes);
        return codec.toView(schemaRepository.save(schema));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApiSchemaRevisionView> listRevisions(UUID connectorId, UUID organizationId, UUID schemaId) {
        requireConnector(connectorId, organizationId);
        requireSchema(connectorId, schemaId);
        return revisionRepository.findBySchemaIdOrderByFetchedAtDesc(schemaId).stream()
                .map(DefaultApiSchemaRevisionService::toRevisionView)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApiSchemaDriftFindingView> listFindings(UUID connectorId, UUID organizationId,
                                                        ApiSchemaDriftStatus status) {
        requireConnector(connectorId, organizationId);
        var rows = status == null
                ? findingRepository.findByConnectorIdOrderByCreatedAtDesc(connectorId)
                : findingRepository.findByConnectorIdAndStatusOrderByCreatedAtDesc(connectorId, status);
        return rows.stream().map(this::toFindingView).toList();
    }

    @Override
    @Transactional
    public AcceptedApiSchemaRevision accept(UUID connectorId, UUID organizationId, UUID schemaId,
                                            UUID revisionId, UUID acceptedBy) {
        requireConnector(connectorId, organizationId);
        var schema = requireSchema(connectorId, schemaId);
        var revision = revisionRepository.findByIdAndSchemaId(revisionId, schemaId)
                .orElseThrow(() -> new ApiSchemaRevisionNotFoundException(revisionId));
        if (revision.getAcceptedAt() != null) {
            throw new ApiSchemaRevisionAlreadyAcceptedException(revisionId);
        }
        var now = clock.instant();
        var operations = codec.readOperations(revision.getParsedOperations());
        // In place: the schema id, its filter and every reference to it stay valid.
        schema.setParsedOperations(revision.getParsedOperations());
        schema.setRawContent(revision.getRawContent());
        schema.setOperationCount(filterMatcher.apply(operations, codec.readFilter(schema)).size());
        schema.setCurrentRevisionId(revisionId);
        revision.setAcceptedAt(now);
        revision.setAcceptedBy(acceptedBy);
        // Every open finding was computed against a catalog that no longer exists.
        for (var finding : findingRepository.findBySchemaIdAndStatus(schemaId, ApiSchemaDriftStatus.OPEN)) {
            finding.setStatus(ApiSchemaDriftStatus.ACCEPTED);
            finding.setResolvedAt(now);
            finding.setResolvedBy(acceptedBy);
        }
        var savedSchema = schemaRepository.save(schema);
        var savedRevision = revisionRepository.save(revision);
        return new AcceptedApiSchemaRevision(codec.toView(savedSchema), toRevisionView(savedRevision));
    }

    @Override
    @Transactional
    public boolean dismissFinding(UUID connectorId, UUID organizationId, UUID findingId, UUID resolvedBy) {
        requireConnector(connectorId, organizationId);
        var finding = findingRepository.findByIdAndConnectorId(findingId, connectorId)
                .orElseThrow(() -> new ApiSchemaDriftFindingNotFoundException(findingId));
        if (finding.getStatus() != ApiSchemaDriftStatus.OPEN) {
            return false;
        }
        finding.setStatus(ApiSchemaDriftStatus.DISMISSED);
        finding.setResolvedAt(clock.instant());
        finding.setResolvedBy(resolvedBy);
        findingRepository.save(finding);
        return true;
    }

    private void requireConnector(UUID connectorId, UUID organizationId) {
        connectorRepository.findByIdAndOrganizationId(connectorId, organizationId)
                .orElseThrow(() -> new ApiConnectorNotFoundException(connectorId));
    }

    private ApiSchemaEntity requireSchema(UUID connectorId, UUID schemaId) {
        return schemaRepository.findByIdAndConnectorId(schemaId, connectorId)
                .orElseThrow(() -> new ApiSchemaNotFoundException(schemaId));
    }

    private static ApiSchemaRevisionView toRevisionView(ApiSchemaRevisionEntity e) {
        return new ApiSchemaRevisionView(e.getId(), e.getSchemaId(), e.getContentHash(), e.getOperationCount(),
                e.getFetchedAt(), e.getLastSeenAt(), e.getAcceptedAt(), e.getAcceptedBy());
    }

    private ApiSchemaDriftFindingView toFindingView(ApiSchemaDriftFindingEntity e) {
        var detail = objectMapper.readTree(e.getDetail() == null ? "{}" : e.getDetail());
        var grants = detail.path("affected_grants");
        return new ApiSchemaDriftFindingView(e.getId(), e.getSchemaId(), e.getRevisionId(), e.getKind(),
                e.getOperationId(), operation(detail.get("before")), operation(detail.get("after")),
                e.getAffectedGrantCount(), uuids(grants.path("users")), uuids(grants.path("groups")),
                e.getStatus(), e.getCreatedAt(), e.getResolvedAt(), e.getResolvedBy());
    }

    private ApiOperation operation(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode()
                ? null : objectMapper.treeToValue(node, ApiOperation.class);
    }

    private static List<UUID> uuids(JsonNode array) {
        var ids = new ArrayList<UUID>();
        if (array.isArray()) {
            array.forEach(node -> ids.add(UUID.fromString(node.asString())));
        }
        return List.copyOf(ids);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record RefreshSource(ApiSchemaType schemaType, String sourceUrl, UUID organizationId) {
    }
}
