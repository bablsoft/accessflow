package com.bablsoft.accessflow.discovery.internal;

import com.bablsoft.accessflow.ai.api.DataDiscoveryAiService;
import com.bablsoft.accessflow.ai.api.DataDiscoveryAiService.DiscoveryColumnSuggestion;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.DataClassificationQueryService;
import com.bablsoft.accessflow.core.api.DataClassificationTagView;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.MaskingPolicyAdminService;
import com.bablsoft.accessflow.core.api.MaskingPolicyView;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.ResultColumn;
import com.bablsoft.accessflow.core.api.SampleTableRequest;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.discovery.api.DiscoveryDetector;
import com.bablsoft.accessflow.discovery.api.DiscoveryFindingStatus;
import com.bablsoft.accessflow.discovery.api.DiscoveryScanAlreadyRunningException;
import com.bablsoft.accessflow.discovery.internal.config.DiscoveryProperties;
import com.bablsoft.accessflow.discovery.internal.persistence.entity.DiscoveryFindingEntity;
import com.bablsoft.accessflow.discovery.internal.persistence.entity.DiscoveryScanConfigEntity;
import com.bablsoft.accessflow.discovery.internal.persistence.repo.DiscoveryFindingRepository;
import com.bablsoft.accessflow.discovery.internal.persistence.repo.DiscoveryScanConfigRepository;
import com.bablsoft.accessflow.proxy.api.QueryExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscoveryScanServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-27T10:00:00Z");

    @Mock
    private DiscoveryScanConfigRepository configRepository;
    @Mock
    private DiscoveryFindingRepository findingRepository;
    @Mock
    private DatasourceAdminService datasourceAdminService;
    @Mock
    private DataClassificationQueryService dataClassificationQueryService;
    @Mock
    private MaskingPolicyAdminService maskingPolicyAdminService;
    @Mock
    private QueryExecutor queryExecutor;
    @Mock
    private DataDiscoveryAiService dataDiscoveryAiService;
    @Mock
    private AuditLogService auditLogService;

    private final UUID dsId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    private DiscoveryScanService service;

    @BeforeEach
    void setUp() {
        service = newService(new DiscoveryProperties(null, null, null, null, null, null, null, null));
    }

    private DiscoveryScanService newService(DiscoveryProperties properties) {
        return newService(properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // The sweep collaborator is real, not mocked: the partial-run guarantees these tests exist to
    // pin live in which tables the scan hands it, so a stub would assert nothing.
    private DiscoveryScanService newService(DiscoveryProperties properties, Clock clock) {
        return new DiscoveryScanService(configRepository, findingRepository,
                datasourceAdminService, dataClassificationQueryService, maskingPolicyAdminService,
                queryExecutor, dataDiscoveryAiService, auditLogService, properties,
                new NestedValueFlattener(properties),
                new DiscoveryStaleSweepService(findingRepository, auditLogService, properties),
                clock);
    }

    /** A clock that advances by {@code step} on every read — drives the scan's time budget. */
    private static Clock steppingClock(Duration step) {
        var cursor = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        return new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return cursor.getAndUpdate(current -> current.plus(step));
            }
        };
    }

    private void stubHappyPath(DatabaseSchemaView schema, SelectExecutionResult result) {
        when(configRepository.findByDatasourceIdAndOrganizationId(dsId, orgId))
                .thenReturn(Optional.empty());
        when(datasourceAdminService.introspectSchemaForSystem(dsId, orgId)).thenReturn(schema);
        lenient().when(dataClassificationQueryService.findByDatasource(dsId, orgId))
                .thenReturn(List.of());
        lenient().when(maskingPolicyAdminService.listForDatasource(dsId, orgId))
                .thenReturn(List.of());
        lenient().when(queryExecutor.sampleTable(any())).thenReturn(result);
        lenient().when(findingRepository.findByNaturalKey(any(), any(), any(), any(), any(), any(),
                any())).thenReturn(Optional.empty());
    }

    private static DatabaseSchemaView schemaWithUsersTable() {
        return new DatabaseSchemaView(List.of(new DatabaseSchemaView.Schema("public", List.of(
                new DatabaseSchemaView.Table("users", List.of(
                        new DatabaseSchemaView.Column("email", "varchar", false, false),
                        new DatabaseSchemaView.Column("name", "varchar", false, false)),
                        List.of())))));
    }

    private static SelectExecutionResult usersSample() {
        var columns = List.of(new ResultColumn("email", 12, "varchar"),
                new ResultColumn("name", 12, "varchar"));
        var rows = List.<List<Object>>of(
                List.of("alice@example.com", "Alice"),
                List.of("bob@example.com", "Bob"),
                List.of("carol@example.com", "Carol"),
                List.of("dave@example.com", "Dave"),
                List.of("erin@example.com", "Erin"));
        return new SelectExecutionResult(columns, rows, rows.size(), false, Duration.ofMillis(5),
                null, null, null);
    }

    @Test
    void createsPendingFindingForEmailColumn() {
        stubHappyPath(schemaWithUsersTable(), usersSample());

        service.scan(dsId, orgId, null);

        var captor = ArgumentCaptor.forClass(DiscoveryFindingEntity.class);
        verify(findingRepository).save(captor.capture());
        var finding = captor.getValue();
        assertThat(finding.getSchemaName()).isEqualTo("public");
        assertThat(finding.getTableName()).isEqualTo("users");
        assertThat(finding.getColumnName()).isEqualTo("email");
        assertThat(finding.getClassification()).isEqualTo(DataClassification.PII);
        assertThat(finding.getDetector()).isEqualTo(DiscoveryDetector.EMAIL);
        assertThat(finding.getConfidence()).isEqualTo(100);
        assertThat(finding.getMatchCount()).isEqualTo(5);
        assertThat(finding.getSampleCount()).isEqualTo(5);
        assertThat(finding.getStatus()).isEqualTo(DiscoveryFindingStatus.PENDING);
        assertThat(finding.getFirstDetectedAt()).isEqualTo(NOW);
        // PARTIAL(visible_suffix=4) — never the raw value.
        assertThat(finding.getSampleRedacted()).endsWith(".com").doesNotContain("alice");
    }

    @Test
    void refreshesPendingFindingInPlace() {
        stubHappyPath(schemaWithUsersTable(), usersSample());
        var existing = new DiscoveryFindingEntity();
        existing.setId(UUID.randomUUID());
        existing.setStatus(DiscoveryFindingStatus.PENDING);
        existing.setFirstDetectedAt(NOW.minus(Duration.ofDays(2)));
        existing.setConfidence(40);
        when(findingRepository.findByNaturalKey(orgId, dsId, "public", "users", "email",
                DataClassification.PII, DiscoveryDetector.EMAIL)).thenReturn(Optional.of(existing));

        service.scan(dsId, orgId, null);

        verify(findingRepository).save(existing);
        assertThat(existing.getConfidence()).isEqualTo(100);
        assertThat(existing.getLastDetectedAt()).isEqualTo(NOW);
        assertThat(existing.getFirstDetectedAt()).isEqualTo(NOW.minus(Duration.ofDays(2)));
    }

    @Test
    void neverTouchesConfirmedOrDismissedFindings() {
        stubHappyPath(schemaWithUsersTable(), usersSample());
        var dismissed = new DiscoveryFindingEntity();
        dismissed.setStatus(DiscoveryFindingStatus.DISMISSED);
        when(findingRepository.findByNaturalKey(orgId, dsId, "public", "users", "email",
                DataClassification.PII, DiscoveryDetector.EMAIL))
                .thenReturn(Optional.of(dismissed));

        service.scan(dsId, orgId, null);

        verify(findingRepository, never()).save(any());
    }

    @Test
    void skipsColumnsAlreadyTagged() {
        stubHappyPath(schemaWithUsersTable(), usersSample());
        when(dataClassificationQueryService.findByDatasource(dsId, orgId)).thenReturn(List.of(
                new DataClassificationTagView(UUID.randomUUID(), dsId, "public.users", "email",
                        DataClassification.PII, null, NOW, NOW)));

        service.scan(dsId, orgId, null);

        verify(findingRepository, never()).save(any());
    }

    @Test
    void skipsColumnsCoveredByEnabledMaskingPolicy() {
        stubHappyPath(schemaWithUsersTable(), usersSample());
        when(maskingPolicyAdminService.listForDatasource(dsId, orgId)).thenReturn(List.of(
                new MaskingPolicyView(UUID.randomUUID(), dsId, "users.email",
                        MaskingStrategy.PARTIAL, Map.of(), List.of(), List.of(), List.of(), true,
                        NOW, NOW)));

        service.scan(dsId, orgId, null);

        verify(findingRepository, never()).save(any());
    }

    @Test
    void ignoresColumnsBelowMinimumSampleCount() {
        var columns = List.of(new ResultColumn("email", 12, "varchar"));
        var rows = List.<List<Object>>of(List.of("alice@example.com"), List.of("bob@example.com"));
        stubHappyPath(schemaWithUsersTable(),
                new SelectExecutionResult(columns, rows, 2, false, Duration.ofMillis(1), null,
                        null, null));

        service.scan(dsId, orgId, null);

        verify(findingRepository, never()).save(any());
    }

    @Test
    void belowMatchRatioProducesNoFinding() {
        var columns = List.of(new ResultColumn("notes", 12, "varchar"));
        var rows = List.<List<Object>>of(
                List.of("call alice@example.com"), List.of("plain note"), List.of("another note"),
                List.of("more text"), List.of("and more"), List.of("last"));
        stubHappyPath(schemaWithUsersTable(),
                new SelectExecutionResult(columns, rows, 6, false, Duration.ofMillis(1), null,
                        null, null));

        service.scan(dsId, orgId, null);

        verify(findingRepository, never()).save(any());
    }

    @Test
    void capsTablesPerScanAndFlagsPartial() {
        var tables = new java.util.ArrayList<DatabaseSchemaView.Table>();
        for (var i = 0; i < 3; i++) {
            tables.add(new DatabaseSchemaView.Table("t" + i, List.of(), List.of()));
        }
        var schema = new DatabaseSchemaView(List.of(
                new DatabaseSchemaView.Schema("public", tables)));
        service = newService(new DiscoveryProperties(null, null, null, 2, null, null, null, null));
        stubHappyPath(schema, new SelectExecutionResult(List.of(), List.of(), 0, false,
                Duration.ofMillis(1), null, null, null));

        service.scan(dsId, orgId, null);

        verify(queryExecutor, org.mockito.Mockito.times(2)).sampleTable(any());
        var audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(audit.capture());
        assertThat(audit.getValue().metadata())
                .containsEntry("partial", true)
                .containsEntry("tablesSkipped", 1)
                .containsEntry("tablesScanned", 2);
    }

    @Test
    void perTableFailureContinuesScan() {
        var schema = new DatabaseSchemaView(List.of(new DatabaseSchemaView.Schema("public",
                List.of(new DatabaseSchemaView.Table("bad", List.of(), List.of()),
                        new DatabaseSchemaView.Table("users", List.of(), List.of())))));
        stubHappyPath(schema, usersSample());
        when(queryExecutor.sampleTable(any()))
                .thenThrow(new IllegalStateException("connection refused"))
                .thenReturn(usersSample());

        service.scan(dsId, orgId, null);

        var audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(audit.capture());
        assertThat(audit.getValue().metadata())
                .containsEntry("tablesFailed", 1)
                .containsEntry("tablesScanned", 1);
    }

    @Test
    void introspectionFailureStampsErrorAndAudits() {
        when(configRepository.findByDatasourceIdAndOrganizationId(dsId, orgId))
                .thenReturn(Optional.empty());
        when(datasourceAdminService.introspectSchemaForSystem(dsId, orgId))
                .thenThrow(new IllegalStateException("unreachable"));

        service.scan(dsId, orgId, null);

        var config = ArgumentCaptor.forClass(DiscoveryScanConfigEntity.class);
        verify(configRepository).save(config.capture());
        assertThat(config.getValue().getLastScanError()).isEqualTo("unreachable");
        assertThat(config.getValue().getLastScanAt()).isEqualTo(NOW);
        var audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(audit.capture());
        assertThat(audit.getValue().action()).isEqualTo(AuditAction.DISCOVERY_SCAN_COMPLETED);
        assertThat(audit.getValue().metadata()).containsEntry("error", "unreachable");
    }

    @Test
    void aiPassRunsOnlyWhenEnabledAndCreatesAiFindings() {
        var config = new DiscoveryScanConfigEntity();
        config.setId(UUID.randomUUID());
        config.setOrganizationId(orgId);
        config.setDatasourceId(dsId);
        config.setAiClassificationEnabled(true);
        config.setSampleSize(50);
        var columns = List.of(new ResultColumn("national_id", 12, "varchar"));
        var rows = List.<List<Object>>of(List.of("11-22-33"), List.of("44-55-66"),
                List.of("77-88-99"), List.of("12-34-56"), List.of("65-43-21"));
        stubHappyPath(schemaWithUsersTable(),
                new SelectExecutionResult(columns, rows, 5, false, Duration.ofMillis(1), null,
                        null, null));
        when(configRepository.findByDatasourceIdAndOrganizationId(dsId, orgId))
                .thenReturn(Optional.of(config));
        when(dataDiscoveryAiService.classifyColumns(eq(orgId), any())).thenReturn(List.of(
                new DiscoveryColumnSuggestion("national_id", DataClassification.SENSITIVE, 70,
                        "identifier-like")));

        service.scan(dsId, orgId, null);

        var context = ArgumentCaptor.forClass(
                DataDiscoveryAiService.DiscoveryTableContext.class);
        verify(dataDiscoveryAiService).classifyColumns(eq(orgId), context.capture());
        // Redacted (format-preserving) samples only — digits become '*'.
        assertThat(context.getValue().columns().getFirst().redactedSamples())
                .allSatisfy(sample -> assertThat(sample).matches("\\*\\*-\\*\\*-\\*\\*"));

        var captor = ArgumentCaptor.forClass(DiscoveryFindingEntity.class);
        verify(findingRepository).save(captor.capture());
        var finding = captor.getValue();
        assertThat(finding.getDetector()).isEqualTo(DiscoveryDetector.AI);
        assertThat(finding.getClassification()).isEqualTo(DataClassification.SENSITIVE);
        assertThat(finding.getConfidence()).isEqualTo(70);
        assertThat(finding.getRationale()).isEqualTo("identifier-like");
        assertThat(finding.getMatchCount()).isZero();
        assertThat(finding.getSampleRedacted()).isEqualTo("**-**-**");
    }

    @Test
    void aiPassSkippedWhenDisabled() {
        stubHappyPath(schemaWithUsersTable(), usersSample());

        service.scan(dsId, orgId, null);

        org.mockito.Mockito.verifyNoInteractions(dataDiscoveryAiService);
    }


    /** A document-engine page: one top-level {@code profile} column holding nested documents. */
    private static SelectExecutionResult nestedProfileSample() {
        var columns = List.of(new ResultColumn("profile", 12, "object"));
        var rows = new java.util.ArrayList<List<Object>>();
        for (var name : List.of("alice", "bob", "carol", "dave", "erin")) {
            rows.add(List.of(Map.of("contact", Map.of("email", name + "@example.com"))));
        }
        return new SelectExecutionResult(columns, rows, rows.size(), false, Duration.ofMillis(1),
                null, null, null);
    }

    @Test
    void createsFindingForNestedDotPathColumn() {
        stubHappyPath(schemaWithUsersTable(), nestedProfileSample());

        service.scan(dsId, orgId, null);

        var captor = ArgumentCaptor.forClass(DiscoveryFindingEntity.class);
        verify(findingRepository).save(captor.capture());
        var finding = captor.getValue();
        assertThat(finding.getColumnName()).isEqualTo("profile.contact.email");
        assertThat(finding.getClassification()).isEqualTo(DataClassification.PII);
        assertThat(finding.getDetector()).isEqualTo(DiscoveryDetector.EMAIL);
        assertThat(finding.getSampleCount()).isEqualTo(5);
        assertThat(finding.getSampleRedacted()).endsWith(".com").doesNotContain("alice");
    }

    @Test
    void createsFindingForFieldInsideArrayOfObjects() {
        var columns = List.of(new ResultColumn("contacts", 12, "array"));
        var rows = List.<List<Object>>of(List.of(List.of(
                Map.of("email", "alice@example.com"), Map.of("email", "bob@example.com"),
                Map.of("email", "carol@example.com"), Map.of("email", "dave@example.com"),
                Map.of("email", "erin@example.com"))));
        stubHappyPath(schemaWithUsersTable(), new SelectExecutionResult(columns, rows, 1, false,
                Duration.ofMillis(1), null, null, null));

        service.scan(dsId, orgId, null);

        var captor = ArgumentCaptor.forClass(DiscoveryFindingEntity.class);
        verify(findingRepository).save(captor.capture());
        // No index segment: every element of the array shares the parent path.
        assertThat(captor.getValue().getColumnName()).isEqualTo("contacts.email");
        assertThat(captor.getValue().getSampleCount()).isEqualTo(5);
    }

    @Test
    void parentObjectColumnItselfProducesNoFinding() {
        stubHappyPath(schemaWithUsersTable(), nestedProfileSample());

        service.scan(dsId, orgId, null);

        var captor = ArgumentCaptor.forClass(DiscoveryFindingEntity.class);
        verify(findingRepository).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(DiscoveryFindingEntity::getColumnName)
                .doesNotContain("profile");
    }

    @Test
    void skipsNestedPathAlreadyTagged() {
        stubHappyPath(schemaWithUsersTable(), nestedProfileSample());
        when(dataClassificationQueryService.findByDatasource(dsId, orgId)).thenReturn(List.of(
                new DataClassificationTagView(UUID.randomUUID(), dsId, "public.users",
                        "profile.contact.email", DataClassification.PII, null, NOW, NOW)));

        service.scan(dsId, orgId, null);

        verify(findingRepository, never()).save(any());
    }

    @Test
    void skipsNestedPathCoveredByEnabledMaskingPolicy() {
        stubHappyPath(schemaWithUsersTable(), nestedProfileSample());
        when(maskingPolicyAdminService.listForDatasource(dsId, orgId)).thenReturn(List.of(
                new MaskingPolicyView(UUID.randomUUID(), dsId, "users.profile.contact.email",
                        MaskingStrategy.PARTIAL, Map.of(), List.of(), List.of(), List.of(), true,
                        NOW, NOW)));

        service.scan(dsId, orgId, null);

        verify(findingRepository, never()).save(any());
    }

    @Test
    void respectsConfiguredNestedDepthLimit() {
        service = newService(new DiscoveryProperties(null, null, null, null, null, 1, null, null));
        stubHappyPath(schemaWithUsersTable(), nestedProfileSample());

        service.scan(dsId, orgId, null);

        verify(findingRepository, never()).save(any());
    }

    @Test
    void respectsConfiguredPerRowLeafBudget() {
        service = newService(new DiscoveryProperties(null, null, null, null, null, null, 1, null));
        stubHappyPath(schemaWithUsersTable(), nestedProfileSample());

        service.scan(dsId, orgId, null);

        verify(findingRepository, never()).save(any());
    }

    @Test
    void nestedAiCandidateCarriesTheDotPathAndNoIntrospectedType() {
        var config = new DiscoveryScanConfigEntity();
        config.setId(UUID.randomUUID());
        config.setOrganizationId(orgId);
        config.setDatasourceId(dsId);
        config.setAiClassificationEnabled(true);
        config.setSampleSize(50);
        var columns = List.of(new ResultColumn("profile", 12, "object"));
        var rows = new java.util.ArrayList<List<Object>>();
        for (var i = 0; i < 5; i++) {
            rows.add(List.of(Map.of("national_id", "11-22-3" + i)));
        }
        stubHappyPath(schemaWithUsersTable(), new SelectExecutionResult(columns, rows, 5, false,
                Duration.ofMillis(1), null, null, null));
        when(configRepository.findByDatasourceIdAndOrganizationId(dsId, orgId))
                .thenReturn(Optional.of(config));
        when(dataDiscoveryAiService.classifyColumns(eq(orgId), any())).thenReturn(List.of());

        service.scan(dsId, orgId, null);

        var context = ArgumentCaptor.forClass(
                DataDiscoveryAiService.DiscoveryTableContext.class);
        verify(dataDiscoveryAiService).classifyColumns(eq(orgId), context.capture());
        var candidate = context.getValue().columns().getFirst();
        assertThat(candidate.name()).isEqualTo("profile.national_id");
        // A dot-path has no introspected column type; the prompt omits it rather than guessing.
        assertThat(candidate.type()).isNull();
    }

    @Test
    void aiSuggestionResolvesTheSampledColumnCaseInsensitively() {
        var config = new DiscoveryScanConfigEntity();
        config.setId(UUID.randomUUID());
        config.setOrganizationId(orgId);
        config.setDatasourceId(dsId);
        config.setAiClassificationEnabled(true);
        config.setSampleSize(50);
        var columns = List.of(new ResultColumn("profile", 12, "object"));
        var rows = new java.util.ArrayList<List<Object>>();
        for (var i = 0; i < 5; i++) {
            rows.add(List.of(Map.of("national_id", "11-22-3" + i)));
        }
        stubHappyPath(schemaWithUsersTable(), new SelectExecutionResult(columns, rows, 5, false,
                Duration.ofMillis(1), null, null, null));
        when(configRepository.findByDatasourceIdAndOrganizationId(dsId, orgId))
                .thenReturn(Optional.of(config));
        when(dataDiscoveryAiService.classifyColumns(eq(orgId), any())).thenReturn(List.of(
                new DiscoveryColumnSuggestion("Profile.National_Id", DataClassification.SENSITIVE,
                        70, "identifier-like")));

        service.scan(dsId, orgId, null);

        var captor = ArgumentCaptor.forClass(DiscoveryFindingEntity.class);
        verify(findingRepository).save(captor.capture());
        // Persisted under the sampled spelling, so a re-cased echo cannot open a second row.
        assertThat(captor.getValue().getColumnName()).isEqualTo("profile.national_id");
        assertThat(captor.getValue().getSampleCount()).isEqualTo(5);
    }

    @Test
    void aiSuggestionForAnUnsampledColumnIsIgnored() {
        var config = new DiscoveryScanConfigEntity();
        config.setId(UUID.randomUUID());
        config.setOrganizationId(orgId);
        config.setDatasourceId(dsId);
        config.setAiClassificationEnabled(true);
        config.setSampleSize(50);
        var columns = List.of(new ResultColumn("national_id", 12, "varchar"));
        var rows = List.<List<Object>>of(List.of("11-22-33"), List.of("44-55-66"),
                List.of("77-88-99"), List.of("12-34-56"), List.of("65-43-21"));
        stubHappyPath(schemaWithUsersTable(), new SelectExecutionResult(columns, rows, 5, false,
                Duration.ofMillis(1), null, null, null));
        when(configRepository.findByDatasourceIdAndOrganizationId(dsId, orgId))
                .thenReturn(Optional.of(config));
        when(dataDiscoveryAiService.classifyColumns(eq(orgId), any())).thenReturn(List.of(
                new DiscoveryColumnSuggestion("hallucinated", DataClassification.SENSITIVE, 70,
                        "made up")));

        service.scan(dsId, orgId, null);

        verify(findingRepository, never()).save(any());
    }

    @Test
    void secondConcurrentScanIsRejected() throws Exception {
        var latch = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(configRepository.findByDatasourceIdAndOrganizationId(dsId, orgId))
                .thenReturn(Optional.empty());
        when(datasourceAdminService.introspectSchemaForSystem(dsId, orgId)).thenAnswer(inv -> {
            latch.countDown();
            release.await();
            return new DatabaseSchemaView(List.of());
        });

        var first = new Thread(() -> service.scan(dsId, orgId, null));
        first.start();
        latch.await();
        try {
            assertThatThrownBy(() -> service.scan(dsId, orgId, null))
                    .isInstanceOf(DiscoveryScanAlreadyRunningException.class);
        } finally {
            release.countDown();
            first.join();
        }
    }

    @Test
    void sampleRequestCarriesConfiguredSampleSizeAndTimeout() {
        var config = new DiscoveryScanConfigEntity();
        config.setId(UUID.randomUUID());
        config.setOrganizationId(orgId);
        config.setDatasourceId(dsId);
        config.setSampleSize(250);
        stubHappyPath(schemaWithUsersTable(), usersSample());
        when(configRepository.findByDatasourceIdAndOrganizationId(dsId, orgId))
                .thenReturn(Optional.of(config));

        service.scan(dsId, orgId, null);

        var request = ArgumentCaptor.forClass(SampleTableRequest.class);
        verify(queryExecutor).sampleTable(request.capture());
        assertThat(request.getValue().maxRowsOverride()).isEqualTo(250);
        assertThat(request.getValue().statementTimeoutOverride())
                .isEqualTo(Duration.ofSeconds(10));
        assertThat(request.getValue().columnMasks()).isEmpty();
        assertThat(request.getValue().rowSecurityPredicates()).isEmpty();
    }

    // --- AF-659: stale-finding ageing -------------------------------------------------------

    /** A PENDING finding on `users` that this run's sample will not re-propose. */
    private DiscoveryFindingEntity stalePendingFinding(String schema, String table, int missed) {
        var entity = new DiscoveryFindingEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setDatasourceId(dsId);
        entity.setSchemaName(schema);
        entity.setTableName(table);
        entity.setColumnName("gone");
        entity.setClassification(DataClassification.PII);
        entity.setDetector(DiscoveryDetector.EMAIL);
        entity.setStatus(DiscoveryFindingStatus.PENDING);
        entity.setMissedScanCount(missed);
        return entity;
    }

    private void stubPendingForSweep(DiscoveryFindingEntity... findings) {
        lenient().when(findingRepository.findAllByDatasourceIdAndOrganizationIdAndStatus(dsId,
                orgId, DiscoveryFindingStatus.PENDING)).thenReturn(List.of(findings));
    }

    @Test
    void agesPendingFindingNoLongerDetectedInAScannedTable() {
        stubHappyPath(schemaWithUsersTable(), usersSample());
        var orphan = stalePendingFinding("public", "users", 0);
        stubPendingForSweep(orphan);

        service.scan(dsId, orgId, null);

        assertThat(orphan.getMissedScanCount()).isEqualTo(1);
        assertThat(orphan.getStatus()).isEqualTo(DiscoveryFindingStatus.PENDING);
    }

    @Test
    void retiresPendingFindingAsStaleAtTheThreshold() {
        service = newService(new DiscoveryProperties(null, null, null, null, null, null, null, 1));
        stubHappyPath(schemaWithUsersTable(), usersSample());
        var orphan = stalePendingFinding("public", "users", 0);
        stubPendingForSweep(orphan);

        service.scan(dsId, orgId, null);

        assertThat(orphan.getStatus()).isEqualTo(DiscoveryFindingStatus.STALE);
        var audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService, org.mockito.Mockito.atLeastOnce()).record(audit.capture());
        assertThat(audit.getAllValues()).anyMatch(
                entry -> entry.action() == AuditAction.DISCOVERY_FINDING_EXPIRED);
        assertThat(audit.getAllValues()).anyMatch(
                entry -> entry.action() == AuditAction.DISCOVERY_SCAN_COMPLETED
                        && entry.metadata().get("findingsExpired").equals(1)
                        && entry.metadata().get("findingsAged").equals(1));
    }

    @Test
    void doesNotAgeFindingsForTablesSkippedByTheTableCap() {
        var schema = new DatabaseSchemaView(List.of(new DatabaseSchemaView.Schema("public",
                List.of(new DatabaseSchemaView.Table("users", List.of(), List.of()),
                        new DatabaseSchemaView.Table("orders", List.of(), List.of())))));
        service = newService(new DiscoveryProperties(null, null, null, 1, null, null, null, 1));
        stubHappyPath(schema, usersSample());
        var orphan = stalePendingFinding("public", "orders", 0);
        stubPendingForSweep(orphan);

        service.scan(dsId, orgId, null);

        assertThat(orphan.getMissedScanCount()).isZero();
        assertThat(orphan.getStatus()).isEqualTo(DiscoveryFindingStatus.PENDING);
    }

    @Test
    void doesNotAgeFindingsForTablesSkippedByTheTimeBudget() {
        var schema = new DatabaseSchemaView(List.of(new DatabaseSchemaView.Schema("public",
                List.of(new DatabaseSchemaView.Table("users", List.of(), List.of()),
                        new DatabaseSchemaView.Table("orders", List.of(), List.of())))));
        // Each clock read advances 6 minutes, so the PT10M budget expires after the first table.
        service = newService(new DiscoveryProperties(null, null, null, null, null, null, null, 1),
                steppingClock(Duration.ofMinutes(6)));
        stubHappyPath(schema, usersSample());
        var orphan = stalePendingFinding("public", "orders", 0);
        stubPendingForSweep(orphan);

        service.scan(dsId, orgId, null);

        assertThat(orphan.getStatus()).isEqualTo(DiscoveryFindingStatus.PENDING);
        assertThat(orphan.getMissedScanCount()).isZero();
    }

    @Test
    void doesNotAgeFindingsForATableWhoseSampleThrew() {
        var schema = new DatabaseSchemaView(List.of(new DatabaseSchemaView.Schema("public",
                List.of(new DatabaseSchemaView.Table("orders", List.of(), List.of())))));
        service = newService(new DiscoveryProperties(null, null, null, null, null, null, null, 1));
        stubHappyPath(schema, usersSample());
        when(queryExecutor.sampleTable(any()))
                .thenThrow(new IllegalStateException("connection refused"));
        var orphan = stalePendingFinding("public", "orders", 0);
        stubPendingForSweep(orphan);

        service.scan(dsId, orgId, null);

        assertThat(orphan.getStatus()).isEqualTo(DiscoveryFindingStatus.PENDING);
        assertThat(orphan.getMissedScanCount()).isZero();
    }

    @Test
    void doesNotAgeFindingsWhenTheSampleYieldedTooFewValues() {
        var columns = List.of(new ResultColumn("email", 12, "varchar"));
        var rows = List.<List<Object>>of(List.of("alice@example.com"), List.of("bob@example.com"));
        service = newService(new DiscoveryProperties(null, null, null, null, null, null, null, 1));
        stubHappyPath(schemaWithUsersTable(),
                new SelectExecutionResult(columns, rows, 2, false, Duration.ofMillis(1), null,
                        null, null));
        var orphan = stalePendingFinding("public", "users", 0);
        stubPendingForSweep(orphan);

        service.scan(dsId, orgId, null);

        assertThat(orphan.getStatus()).isEqualTo(DiscoveryFindingStatus.PENDING);
        assertThat(orphan.getMissedScanCount()).isZero();
    }

    @Test
    void doesNotAgeFindingsWhenSchemaIntrospectionFails() {
        when(configRepository.findByDatasourceIdAndOrganizationId(dsId, orgId))
                .thenReturn(Optional.empty());
        when(datasourceAdminService.introspectSchemaForSystem(dsId, orgId))
                .thenThrow(new IllegalStateException("introspection failed"));
        var orphan = stalePendingFinding("public", "users", 0);
        stubPendingForSweep(orphan);

        service.scan(dsId, orgId, null);

        assertThat(orphan.getMissedScanCount()).isZero();
        verify(findingRepository, never()).save(any());
    }

    @Test
    void revivesStaleFindingToPendingOnRedetection() {
        stubHappyPath(schemaWithUsersTable(), usersSample());
        var stale = new DiscoveryFindingEntity();
        stale.setId(UUID.randomUUID());
        stale.setStatus(DiscoveryFindingStatus.STALE);
        stale.setMissedScanCount(3);
        stale.setFirstDetectedAt(NOW.minus(Duration.ofDays(9)));
        when(findingRepository.findByNaturalKey(orgId, dsId, "public", "users", "email",
                DataClassification.PII, DiscoveryDetector.EMAIL)).thenReturn(Optional.of(stale));

        service.scan(dsId, orgId, null);

        assertThat(stale.getStatus()).isEqualTo(DiscoveryFindingStatus.PENDING);
        assertThat(stale.getMissedScanCount()).isZero();
        assertThat(stale.getFirstDetectedAt()).isEqualTo(NOW.minus(Duration.ofDays(9)));
        assertThat(stale.getLastDetectedAt()).isEqualTo(NOW);
    }

    @Test
    void resetsMissedScanCountWhenAFindingIsRedetected() {
        stubHappyPath(schemaWithUsersTable(), usersSample());
        var existing = new DiscoveryFindingEntity();
        existing.setId(UUID.randomUUID());
        existing.setStatus(DiscoveryFindingStatus.PENDING);
        existing.setMissedScanCount(2);
        existing.setFirstDetectedAt(NOW.minus(Duration.ofDays(1)));
        when(findingRepository.findByNaturalKey(orgId, dsId, "public", "users", "email",
                DataClassification.PII, DiscoveryDetector.EMAIL))
                .thenReturn(Optional.of(existing));
        stubPendingForSweep(existing);

        service.scan(dsId, orgId, null);

        assertThat(existing.getMissedScanCount()).isZero();
        assertThat(existing.getStatus()).isEqualTo(DiscoveryFindingStatus.PENDING);
    }

    @Test
    void doesNotFailTheScanWhenTheSweepThrows() {
        stubHappyPath(schemaWithUsersTable(), usersSample());
        when(findingRepository.findAllByDatasourceIdAndOrganizationIdAndStatus(dsId, orgId,
                DiscoveryFindingStatus.PENDING)).thenThrow(new IllegalStateException("db down"));

        service.scan(dsId, orgId, null);

        var config = ArgumentCaptor.forClass(DiscoveryScanConfigEntity.class);
        verify(configRepository).save(config.capture());
        assertThat(config.getValue().getLastScanError()).isNull();
    }


    /** An AI-detector finding on `users` the current run will not re-propose. */
    private DiscoveryFindingEntity staleAiFinding() {
        var entity = stalePendingFinding("public", "users", 0);
        entity.setDetector(DiscoveryDetector.AI);
        return entity;
    }

    private DiscoveryScanConfigEntity aiEnabledConfig() {
        var config = new DiscoveryScanConfigEntity();
        config.setId(UUID.randomUUID());
        config.setOrganizationId(orgId);
        config.setDatasourceId(dsId);
        config.setAiClassificationEnabled(true);
        config.setSampleSize(50);
        return config;
    }

    @Test
    void doesNotAgeAiFindingsWhenTheAiPassReturnedNothing() {
        // The AI lane is fail-safe: a rotated key, an outage or a deleted ai_config yields an
        // empty list, not an exception. Ageing on that would retire every AI finding in the estate.
        service = newService(new DiscoveryProperties(null, null, null, null, null, null, null, 1));
        var columns = List.of(new ResultColumn("national_id", 12, "varchar"));
        var rows = List.<List<Object>>of(List.of("11-22-33"), List.of("44-55-66"),
                List.of("77-88-99"), List.of("12-34-56"), List.of("65-43-21"));
        stubHappyPath(schemaWithUsersTable(),
                new SelectExecutionResult(columns, rows, 5, false, Duration.ofMillis(1), null,
                        null, null));
        when(configRepository.findByDatasourceIdAndOrganizationId(dsId, orgId))
                .thenReturn(Optional.of(aiEnabledConfig()));
        when(dataDiscoveryAiService.classifyColumns(eq(orgId), any())).thenReturn(List.of());
        var orphan = staleAiFinding();
        stubPendingForSweep(orphan);

        service.scan(dsId, orgId, null);

        assertThat(orphan.getStatus()).isEqualTo(DiscoveryFindingStatus.PENDING);
        assertThat(orphan.getMissedScanCount()).isZero();
    }

    @Test
    void agesAiFindingsWhenTheAiPassAnsweredForTheTable() {
        service = newService(new DiscoveryProperties(null, null, null, null, null, null, null, 1));
        var columns = List.of(new ResultColumn("national_id", 12, "varchar"));
        var rows = List.<List<Object>>of(List.of("11-22-33"), List.of("44-55-66"),
                List.of("77-88-99"), List.of("12-34-56"), List.of("65-43-21"));
        stubHappyPath(schemaWithUsersTable(),
                new SelectExecutionResult(columns, rows, 5, false, Duration.ofMillis(1), null,
                        null, null));
        when(configRepository.findByDatasourceIdAndOrganizationId(dsId, orgId))
                .thenReturn(Optional.of(aiEnabledConfig()));
        when(dataDiscoveryAiService.classifyColumns(eq(orgId), any())).thenReturn(List.of(
                new DiscoveryColumnSuggestion("national_id", DataClassification.SENSITIVE, 70,
                        "identifier-like")));
        var orphan = staleAiFinding();
        stubPendingForSweep(orphan);

        service.scan(dsId, orgId, null);

        assertThat(orphan.getStatus()).isEqualTo(DiscoveryFindingStatus.STALE);
    }

    @Test
    void doesNotAgeAiFindingsWhenTheAiPassIsDisabled() {
        service = newService(new DiscoveryProperties(null, null, null, null, null, null, null, 1));
        stubHappyPath(schemaWithUsersTable(), usersSample());
        var orphan = staleAiFinding();
        stubPendingForSweep(orphan);

        service.scan(dsId, orgId, null);

        assertThat(orphan.getStatus()).isEqualTo(DiscoveryFindingStatus.PENDING);
    }

    @Test
    void stillAgesRegexFindingsWhenTheAiPassReturnedNothing() {
        // The AI gate is scoped to AI-detector findings; a regex proposal must still age.
        service = newService(new DiscoveryProperties(null, null, null, null, null, null, null, 1));
        stubHappyPath(schemaWithUsersTable(), usersSample());
        when(configRepository.findByDatasourceIdAndOrganizationId(dsId, orgId))
                .thenReturn(Optional.of(aiEnabledConfig()));
        lenient().when(dataDiscoveryAiService.classifyColumns(eq(orgId), any()))
                .thenReturn(List.of());
        var orphan = stalePendingFinding("public", "users", 0);
        stubPendingForSweep(orphan);

        service.scan(dsId, orgId, null);

        assertThat(orphan.getStatus()).isEqualTo(DiscoveryFindingStatus.STALE);
    }
}
