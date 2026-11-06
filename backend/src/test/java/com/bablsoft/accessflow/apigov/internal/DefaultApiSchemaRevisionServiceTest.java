package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiSchemaDriftFindingNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaDriftKind;
import com.bablsoft.accessflow.apigov.api.ApiSchemaDriftStatus;
import com.bablsoft.accessflow.apigov.api.ApiSchemaFetchException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaNotRefreshableException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaParseException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaRevisionAlreadyAcceptedException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaRevisionNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import com.bablsoft.accessflow.apigov.api.OperationFilter;
import com.bablsoft.accessflow.apigov.api.ParsedApiSchema;
import com.bablsoft.accessflow.apigov.events.ApiSchemaDriftDetectedEvent;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
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
import com.bablsoft.accessflow.apigov.internal.schema.OperationFilterMatcher;
import com.bablsoft.accessflow.apigov.internal.schema.SchemaParserRegistry;
import com.bablsoft.accessflow.apigov.internal.schema.SchemaSourceFetcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultApiSchemaRevisionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
    private static final String URL = "https://example.test/openapi.json";

    @Mock private ApiConnectorRepository connectorRepository;
    @Mock private ApiSchemaRepository schemaRepository;
    @Mock private ApiSchemaRevisionRepository revisionRepository;
    @Mock private ApiSchemaDriftFindingRepository findingRepository;
    @Mock private ApiConnectorUserPermissionRepository userPermissionRepository;
    @Mock private ApiConnectorGroupPermissionRepository groupPermissionRepository;
    @Mock private SchemaSourceFetcher sourceFetcher;
    @Mock private SchemaParserRegistry parserRegistry;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final ApiSchemaCatalogCodec codec = new ApiSchemaCatalogCodec(objectMapper);
    private final OperationCatalogDiff catalogDiff = new OperationCatalogDiff(objectMapper);
    private DefaultApiSchemaRevisionService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();
    private final UUID schemaId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    private final ApiOperation listPets = new ApiOperation("listPets", "GET", "/pets", null, false, null, null);
    private final ApiOperation createPet = new ApiOperation("createPet", "POST", "/pets", null, true, null, null);
    private final ApiOperation deletePet = new ApiOperation("deletePet", "DELETE", "/pets/{id}", null, true, null,
            null);

    @BeforeEach
    void setUp() {
        service = new DefaultApiSchemaRevisionService(connectorRepository, schemaRepository, revisionRepository,
                findingRepository, userPermissionRepository, groupPermissionRepository, sourceFetcher,
                parserRegistry, new OperationFilterMatcher(), catalogDiff, codec, objectMapper, eventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);
    }

    private void connectorExists() {
        var connector = new ApiConnectorEntity();
        connector.setId(connectorId);
        connector.setOrganizationId(orgId);
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId)).thenReturn(Optional.of(connector));
    }

    private void connectorMissing() {
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId)).thenReturn(Optional.empty());
    }

    private ApiSchemaEntity schema(String sourceUrl, List<ApiOperation> current) {
        var schema = new ApiSchemaEntity();
        schema.setId(schemaId);
        schema.setConnectorId(connectorId);
        schema.setSchemaType(ApiSchemaType.OPENAPI);
        schema.setSourceUrl(sourceUrl);
        schema.setParsedOperations(codec.writeOperations(current));
        schema.setOperationCount(current.size());
        schema.setRawContent("old-doc");
        when(schemaRepository.findByIdAndConnectorId(schemaId, connectorId)).thenReturn(Optional.of(schema));
        return schema;
    }

    private void upstreamServes(List<ApiOperation> operations) {
        when(sourceFetcher.fetch(URL)).thenReturn("new-doc");
        when(parserRegistry.parse(ApiSchemaType.OPENAPI, "new-doc")).thenReturn(new ParsedApiSchema(operations));
    }

    private List<ApiSchemaDriftFindingEntity> savedFindings() {
        var captor = ArgumentCaptor.forClass(ApiSchemaDriftFindingEntity.class);
        verify(findingRepository, org.mockito.Mockito.atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    // ---- refresh ----

    @Test
    void firstRefreshWithNoRevisionDiffsAgainstTheCurrentCatalog() {
        connectorExists();
        var schema = schema(URL, List.of(listPets, createPet));
        upstreamServes(List.of(listPets));

        var result = service.refresh(connectorId, orgId, schemaId);

        assertThat(result.unchanged()).isFalse();
        assertThat(result.revisionId()).isNotNull();
        assertThat(result.findingCount()).isEqualTo(1);
        assertThat(savedFindings()).extracting(ApiSchemaDriftFindingEntity::getKind,
                        ApiSchemaDriftFindingEntity::getOperationId)
                .containsExactly(tuple(ApiSchemaDriftKind.OPERATION_REMOVED, "createPet"));
        // The governed catalog itself is untouched by a refresh.
        assertThat(codec.readOperations(schema.getParsedOperations())).containsExactly(listPets, createPet);
        assertThat(schema.getLastRefreshAt()).isEqualTo(NOW);
    }

    @Test
    void anUnchangedHashBumpsLastSeenAtOnly() {
        connectorExists();
        var schema = schema(URL, List.of(listPets));
        schema.setLastRefreshError("old failure");
        upstreamServes(List.of(listPets, createPet));
        var existing = new ApiSchemaRevisionEntity();
        existing.setLastSeenAt(NOW.minusSeconds(3600));
        when(revisionRepository.findBySchemaIdAndContentHash(schemaId,
                catalogDiff.contentHash(List.of(listPets, createPet)))).thenReturn(Optional.of(existing));

        var result = service.refresh(connectorId, orgId, schemaId);

        assertThat(result.unchanged()).isTrue();
        assertThat(result.revisionId()).isNull();
        assertThat(existing.getLastSeenAt()).isEqualTo(NOW);
        assertThat(schema.getLastRefreshError()).isNull();
        verify(revisionRepository, never()).save(any());
        verifyNoInteractions(findingRepository, eventPublisher);
    }

    @Test
    void aCatalogEqualToTheCurrentOneIsUnchangedEvenWithoutARevision() {
        connectorExists();
        schema(URL, List.of(listPets, createPet));
        upstreamServes(List.of(createPet, listPets));

        var result = service.refresh(connectorId, orgId, schemaId);

        assertThat(result.unchanged()).isTrue();
        verify(revisionRepository, never()).save(any());
        verifyNoInteractions(findingRepository, eventPublisher);
    }

    @Test
    void aChangedCatalogInsertsOneRevisionWithTheExactFindingsAndPublishesOnce() {
        connectorExists();
        schema(URL, List.of(listPets, createPet));
        var listPetsNowWrites = new ApiOperation("listPets", "GET", "/pets", null, true, null, null);
        upstreamServes(List.of(listPetsNowWrites, deletePet));

        var result = service.refresh(connectorId, orgId, schemaId);

        var revisionCaptor = ArgumentCaptor.forClass(ApiSchemaRevisionEntity.class);
        verify(revisionRepository).save(revisionCaptor.capture());
        var revision = revisionCaptor.getValue();
        assertThat(revision.getId()).isEqualTo(result.revisionId());
        assertThat(revision.getContentHash()).isEqualTo(catalogDiff.contentHash(List.of(listPetsNowWrites, deletePet)));
        assertThat(revision.getOperationCount()).isEqualTo(2);
        assertThat(revision.getRawContent()).isEqualTo("new-doc");
        assertThat(revision.getFetchedAt()).isEqualTo(NOW);
        assertThat(revision.getLastSeenAt()).isEqualTo(NOW);
        assertThat(savedFindings()).extracting(ApiSchemaDriftFindingEntity::getKind,
                        ApiSchemaDriftFindingEntity::getOperationId)
                .containsExactly(
                        tuple(ApiSchemaDriftKind.OPERATION_REMOVED, "createPet"),
                        tuple(ApiSchemaDriftKind.OPERATION_ADDED, "deletePet"),
                        tuple(ApiSchemaDriftKind.CLASSIFICATION_FLIPPED, "listPets"));
        assertThat(savedFindings()).allSatisfy(f -> {
            assertThat(f.getStatus()).isEqualTo(ApiSchemaDriftStatus.OPEN);
            assertThat(f.getOrganizationId()).isEqualTo(orgId);
            assertThat(f.getRevisionId()).isEqualTo(revision.getId());
        });
        verify(eventPublisher).publishEvent(new ApiSchemaDriftDetectedEvent(schemaId, connectorId, orgId,
                revision.getId(), 3));
    }

    @Test
    void findingsAreComputedAfterTheFilterOnBothSides() {
        connectorExists();
        var schema = schema(URL, List.of(listPets, new ApiOperation("sync", "POST", "/internal/sync", null, true,
                null, null)));
        schema.setOperationFilter(codec.writeFilter(
                new OperationFilter(null, List.of("/internal/**"), null, null, null, null, null, null, false)));
        upstreamServes(List.of(listPets, new ApiOperation("purge", "POST", "/internal/purge", null, true, null,
                null)));

        var result = service.refresh(connectorId, orgId, schemaId);

        assertThat(result.unchanged()).isFalse();
        assertThat(result.findingCount()).isZero();
        verify(revisionRepository).save(any());
        verify(findingRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void theParserSanitizedContentIsWhatTheRevisionStores() {
        connectorExists();
        schema(URL, List.of(listPets));
        when(sourceFetcher.fetch(URL)).thenReturn("{\"token\":\"SECRET\"}");
        when(parserRegistry.parse(ApiSchemaType.OPENAPI, "{\"token\":\"SECRET\"}"))
                .thenReturn(new ParsedApiSchema(List.of(createPet), null, "{\"redacted\":true}"));

        service.refresh(connectorId, orgId, schemaId);

        var captor = ArgumentCaptor.forClass(ApiSchemaRevisionEntity.class);
        verify(revisionRepository).save(captor.capture());
        assertThat(captor.getValue().getRawContent()).isEqualTo("{\"redacted\":true}");
    }

    @Test
    void aRemovedOperationRecordsEveryGrantThatNamesIt() {
        connectorExists();
        schema(URL, List.of(listPets, createPet));
        upstreamServes(List.of(listPets));
        var user1 = UUID.randomUUID();
        var user2 = UUID.randomUUID();
        var group1 = UUID.randomUUID();
        when(userPermissionRepository.findIdsByConnectorIdAndAllowedOperation(connectorId, "createPet"))
                .thenReturn(List.of(user1, user2));
        when(groupPermissionRepository.findIdsByConnectorIdAndAllowedOperation(connectorId, "createPet"))
                .thenReturn(List.of(group1));

        service.refresh(connectorId, orgId, schemaId);

        var finding = savedFindings().getFirst();
        assertThat(finding.getAffectedGrantCount()).isEqualTo(3);
        // Round-trips into the read view.
        when(findingRepository.findByConnectorIdOrderByCreatedAtDesc(connectorId)).thenReturn(List.of(finding));
        var view = service.listFindings(connectorId, orgId, null).getFirst();
        assertThat(view.affectedGrantCount()).isEqualTo(3);
        assertThat(view.affectedUserGrantIds()).containsExactly(user1, user2);
        assertThat(view.affectedGroupGrantIds()).containsExactly(group1);
        assertThat(view.before()).isEqualTo(createPet);
        assertThat(view.after()).isNull();
    }

    @Test
    void aFetchFailureRecordsTheErrorAndProducesNothing() {
        connectorExists();
        var schema = schema(URL, List.of(listPets));
        when(schemaRepository.findById(schemaId)).thenReturn(Optional.of(schema));
        when(sourceFetcher.fetch(URL)).thenThrow(new ApiSchemaFetchException("Schema URL returned HTTP 502"));

        assertThatThrownBy(() -> service.refresh(connectorId, orgId, schemaId))
                .isInstanceOf(ApiSchemaFetchException.class);

        assertThat(schema.getLastRefreshError()).isEqualTo("Schema URL returned HTTP 502");
        assertThat(schema.getLastRefreshAt()).isEqualTo(NOW);
        verify(revisionRepository, never()).save(any());
        verifyNoInteractions(findingRepository, eventPublisher, parserRegistry);
    }

    @Test
    void aParseFailureRecordsATruncatedErrorAndProducesNothing() {
        connectorExists();
        var schema = schema(URL, List.of(listPets));
        when(schemaRepository.findById(schemaId)).thenReturn(Optional.of(schema));
        when(sourceFetcher.fetch(URL)).thenReturn("garbage");
        when(parserRegistry.parse(ApiSchemaType.OPENAPI, "garbage"))
                .thenThrow(new ApiSchemaParseException("x".repeat(5000)));

        assertThatThrownBy(() -> service.refresh(connectorId, orgId, schemaId))
                .isInstanceOf(ApiSchemaParseException.class);

        assertThat(schema.getLastRefreshError()).hasSize(DefaultApiSchemaRevisionService.MAX_ERROR_LENGTH);
        verify(revisionRepository, never()).save(any());
        verifyNoInteractions(findingRepository, eventPublisher);
    }

    @Test
    void aFailureWithoutAMessageStillRecordsAnError() {
        connectorExists();
        var schema = schema(URL, List.of(listPets));
        when(schemaRepository.findById(schemaId)).thenReturn(Optional.of(schema));
        when(sourceFetcher.fetch(URL)).thenThrow(new ApiSchemaFetchException(null));

        assertThatThrownBy(() -> service.refresh(connectorId, orgId, schemaId))
                .isInstanceOf(ApiSchemaFetchException.class);

        assertThat(schema.getLastRefreshError()).isEqualTo("Schema refresh failed");
    }

    @Test
    void refreshRejectsASchemaWithoutSourceUrl() {
        connectorExists();
        schema(null, List.of(listPets));

        assertThatThrownBy(() -> service.refresh(connectorId, orgId, schemaId))
                .isInstanceOf(ApiSchemaNotRefreshableException.class);
        verifyNoInteractions(sourceFetcher);
    }

    @Test
    void refreshRejectsAConnectorOutsideTheOrganization() {
        connectorMissing();

        assertThatThrownBy(() -> service.refresh(connectorId, orgId, schemaId))
                .isInstanceOf(ApiConnectorNotFoundException.class);
        verifyNoInteractions(sourceFetcher);
    }

    @Test
    void refreshRejectsAnUnknownSchema() {
        connectorExists();

        assertThatThrownBy(() -> service.refresh(connectorId, orgId, schemaId))
                .isInstanceOf(ApiSchemaNotFoundException.class);
    }

    // ---- refresh policy ----

    @Test
    void updateRefreshPolicySetsAndClearsTheInterval() {
        connectorExists();
        var schema = schema(URL, List.of(listPets));
        when(schemaRepository.save(schema)).thenReturn(schema);

        assertThat(service.updateRefreshPolicy(connectorId, orgId, schemaId, 30).refreshIntervalMinutes())
                .isEqualTo(30);
        assertThat(service.updateRefreshPolicy(connectorId, orgId, schemaId, null).refreshIntervalMinutes())
                .isNull();
    }

    @Test
    void updateRefreshPolicyRejectsASchemaWithoutSourceUrl() {
        connectorExists();
        schema(" ", List.of(listPets));

        assertThatThrownBy(() -> service.updateRefreshPolicy(connectorId, orgId, schemaId, 30))
                .isInstanceOf(ApiSchemaNotRefreshableException.class);
    }

    @Test
    void updateRefreshPolicyRejectsAConnectorOutsideTheOrganization() {
        connectorMissing();

        assertThatThrownBy(() -> service.updateRefreshPolicy(connectorId, orgId, schemaId, 30))
                .isInstanceOf(ApiConnectorNotFoundException.class);
    }

    // ---- listings ----

    @Test
    void listRevisionsMapsNewestFirst() {
        connectorExists();
        schema(URL, List.of(listPets));
        var revision = revision(List.of(listPets));
        when(revisionRepository.findBySchemaIdOrderByFetchedAtDesc(schemaId)).thenReturn(List.of(revision));

        var listed = service.listRevisions(connectorId, orgId, schemaId);

        assertThat(listed).singleElement().satisfies(v -> {
            assertThat(v.id()).isEqualTo(revision.getId());
            assertThat(v.contentHash()).isEqualTo(revision.getContentHash());
            assertThat(v.operationCount()).isEqualTo(1);
        });
    }

    @Test
    void listRevisionsRejectsAConnectorOutsideTheOrganization() {
        connectorMissing();

        assertThatThrownBy(() -> service.listRevisions(connectorId, orgId, schemaId))
                .isInstanceOf(ApiConnectorNotFoundException.class);
    }

    @Test
    void listFindingsFiltersByStatus() {
        connectorExists();
        var finding = finding(ApiSchemaDriftStatus.DISMISSED);
        finding.setDetail("{\"before\":null,\"after\":{\"operationId\":\"x\",\"verb\":\"GET\",\"path\":\"/x\","
                + "\"write\":false}}");
        when(findingRepository.findByConnectorIdAndStatusOrderByCreatedAtDesc(connectorId,
                ApiSchemaDriftStatus.DISMISSED)).thenReturn(List.of(finding));

        var listed = service.listFindings(connectorId, orgId, ApiSchemaDriftStatus.DISMISSED);

        assertThat(listed).singleElement().satisfies(v -> {
            assertThat(v.status()).isEqualTo(ApiSchemaDriftStatus.DISMISSED);
            assertThat(v.before()).isNull();
            assertThat(v.after().operationId()).isEqualTo("x");
            assertThat(v.affectedUserGrantIds()).isEmpty();
        });
    }

    @Test
    void listFindingsRejectsAConnectorOutsideTheOrganization() {
        connectorMissing();

        assertThatThrownBy(() -> service.listFindings(connectorId, orgId, null))
                .isInstanceOf(ApiConnectorNotFoundException.class);
    }

    // ---- accept ----

    @Test
    void acceptRewritesTheSchemaInPlaceKeepsTheFilterAndAcceptsOpenFindings() {
        connectorExists();
        var schema = schema(URL, List.of(listPets));
        var filterJson = codec.writeFilter(
                new OperationFilter(null, null, null, List.of("DELETE"), null, null, null, null, false));
        schema.setOperationFilter(filterJson);
        var revision = revision(List.of(listPets, createPet, deletePet));
        revision.setRawContent("new-doc");
        when(revisionRepository.findByIdAndSchemaId(revision.getId(), schemaId)).thenReturn(Optional.of(revision));
        var open = new ArrayList<ApiSchemaDriftFindingEntity>();
        open.add(finding(ApiSchemaDriftStatus.OPEN));
        open.add(finding(ApiSchemaDriftStatus.OPEN));
        when(findingRepository.findBySchemaIdAndStatus(schemaId, ApiSchemaDriftStatus.OPEN)).thenReturn(open);
        when(schemaRepository.save(schema)).thenReturn(schema);
        when(revisionRepository.save(revision)).thenReturn(revision);

        var accepted = service.accept(connectorId, orgId, schemaId, revision.getId(), adminId);

        assertThat(schema.getId()).isEqualTo(schemaId);
        assertThat(schema.getParsedOperations()).isEqualTo(revision.getParsedOperations());
        assertThat(schema.getRawContent()).isEqualTo("new-doc");
        assertThat(schema.getOperationFilter()).isEqualTo(filterJson);
        assertThat(schema.getOperationCount()).isEqualTo(2); // DELETE excluded by the kept filter
        assertThat(schema.getCurrentRevisionId()).isEqualTo(revision.getId());
        assertThat(revision.getAcceptedAt()).isEqualTo(NOW);
        assertThat(revision.getAcceptedBy()).isEqualTo(adminId);
        assertThat(open).allSatisfy(f -> {
            assertThat(f.getStatus()).isEqualTo(ApiSchemaDriftStatus.ACCEPTED);
            assertThat(f.getResolvedAt()).isEqualTo(NOW);
            assertThat(f.getResolvedBy()).isEqualTo(adminId);
        });
        assertThat(accepted.schema().currentRevisionId()).isEqualTo(revision.getId());
        assertThat(accepted.schema().totalOperationCount()).isEqualTo(3);
        assertThat(accepted.revision().contentHash()).isEqualTo(revision.getContentHash());
    }

    @Test
    void acceptingTwiceIsAConflict() {
        connectorExists();
        schema(URL, List.of(listPets));
        var revision = revision(List.of(createPet));
        revision.setAcceptedAt(NOW.minusSeconds(60));
        when(revisionRepository.findByIdAndSchemaId(revision.getId(), schemaId)).thenReturn(Optional.of(revision));

        assertThatThrownBy(() -> service.accept(connectorId, orgId, schemaId, revision.getId(), adminId))
                .isInstanceOf(ApiSchemaRevisionAlreadyAcceptedException.class);
        verify(schemaRepository, never()).save(any());
    }

    @Test
    void acceptRejectsARevisionOfAnotherSchema() {
        connectorExists();
        schema(URL, List.of(listPets));
        var revisionId = UUID.randomUUID();

        assertThatThrownBy(() -> service.accept(connectorId, orgId, schemaId, revisionId, adminId))
                .isInstanceOf(ApiSchemaRevisionNotFoundException.class);
    }

    @Test
    void acceptRejectsAConnectorOutsideTheOrganization() {
        connectorMissing();

        assertThatThrownBy(() -> service.accept(connectorId, orgId, schemaId, UUID.randomUUID(), adminId))
                .isInstanceOf(ApiConnectorNotFoundException.class);
    }

    // ---- dismiss ----

    @Test
    void dismissMarksAnOpenFindingDismissed() {
        connectorExists();
        var finding = finding(ApiSchemaDriftStatus.OPEN);
        when(findingRepository.findByIdAndConnectorId(finding.getId(), connectorId)).thenReturn(Optional.of(finding));

        assertThat(service.dismissFinding(connectorId, orgId, finding.getId(), adminId)).isTrue();

        assertThat(finding.getStatus()).isEqualTo(ApiSchemaDriftStatus.DISMISSED);
        assertThat(finding.getResolvedBy()).isEqualTo(adminId);
        assertThat(finding.getResolvedAt()).isEqualTo(NOW);
        verify(findingRepository).save(finding);
    }

    @Test
    void dismissLeavesAFindingThatIsNoLongerOpen() {
        connectorExists();
        var finding = finding(ApiSchemaDriftStatus.ACCEPTED);
        when(findingRepository.findByIdAndConnectorId(finding.getId(), connectorId)).thenReturn(Optional.of(finding));

        assertThat(service.dismissFinding(connectorId, orgId, finding.getId(), adminId)).isFalse();

        assertThat(finding.getStatus()).isEqualTo(ApiSchemaDriftStatus.ACCEPTED);
        verify(findingRepository, never()).save(any());
    }

    @Test
    void dismissRejectsAnUnknownFinding() {
        connectorExists();
        var findingId = UUID.randomUUID();
        when(findingRepository.findByIdAndConnectorId(eq(findingId), eq(connectorId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.dismissFinding(connectorId, orgId, findingId, adminId))
                .isInstanceOf(ApiSchemaDriftFindingNotFoundException.class);
    }

    @Test
    void dismissRejectsAConnectorOutsideTheOrganization() {
        connectorMissing();

        assertThatThrownBy(() -> service.dismissFinding(connectorId, orgId, UUID.randomUUID(), adminId))
                .isInstanceOf(ApiConnectorNotFoundException.class);
        verifyNoInteractions(findingRepository);
    }

    private ApiSchemaRevisionEntity revision(List<ApiOperation> operations) {
        var revision = new ApiSchemaRevisionEntity();
        revision.setId(UUID.randomUUID());
        revision.setSchemaId(schemaId);
        revision.setConnectorId(connectorId);
        revision.setContentHash(catalogDiff.contentHash(operations));
        revision.setParsedOperations(codec.writeOperations(operations));
        revision.setOperationCount(operations.size());
        revision.setFetchedAt(NOW.minusSeconds(600));
        revision.setLastSeenAt(NOW.minusSeconds(600));
        return revision;
    }

    private ApiSchemaDriftFindingEntity finding(ApiSchemaDriftStatus status) {
        var finding = new ApiSchemaDriftFindingEntity();
        finding.setId(UUID.randomUUID());
        finding.setOrganizationId(orgId);
        finding.setConnectorId(connectorId);
        finding.setSchemaId(schemaId);
        finding.setRevisionId(UUID.randomUUID());
        finding.setKind(ApiSchemaDriftKind.OPERATION_ADDED);
        finding.setOperationId("x");
        finding.setStatus(status);
        return finding;
    }
}
