package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.apigov.api.ApiConnectorHealthStatus;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.api.ApiSchemaDriftKind;
import com.bablsoft.accessflow.apigov.api.ApiSchemaDriftStatus;
import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorHealthCheckEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiSchemaDriftFindingEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiSchemaEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiSchemaRevisionEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Entity ↔ DDL parity for the #891 drift/health tables under {@code ddl-auto=validate}: every pg
 * enum round-trips, the cascades fire, and {@code (schema_id, content_hash)} is unique.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ApiSchemaDriftPersistenceIntegrationTest {

    @Autowired
    private ApiConnectorRepository connectorRepository;

    @Autowired
    private ApiSchemaRepository schemaRepository;

    @Autowired
    private ApiSchemaRevisionRepository revisionRepository;

    @Autowired
    private ApiSchemaDriftFindingRepository findingRepository;

    @Autowired
    private ApiConnectorHealthCheckRepository healthCheckRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private ApiConnectorEntity connector() {
        var e = new ApiConnectorEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(UUID.randomUUID());
        e.setName("drift-" + UUID.randomUUID());
        e.setProtocol(ApiProtocol.REST);
        e.setBaseUrl("https://api.example.com");
        return connectorRepository.save(e);
    }

    private ApiSchemaEntity schema(ApiConnectorEntity connector) {
        var e = new ApiSchemaEntity();
        e.setId(UUID.randomUUID());
        e.setConnectorId(connector.getId());
        e.setSchemaType(ApiSchemaType.OPENAPI);
        e.setSourceUrl("https://api.example.com/openapi.json");
        return schemaRepository.save(e);
    }

    private ApiSchemaRevisionEntity revision(ApiSchemaEntity schema, String hash, Instant fetchedAt) {
        var e = new ApiSchemaRevisionEntity();
        e.setId(UUID.randomUUID());
        e.setSchemaId(schema.getId());
        e.setConnectorId(schema.getConnectorId());
        e.setContentHash(hash);
        e.setFetchedAt(fetchedAt);
        e.setLastSeenAt(fetchedAt);
        return e;
    }

    private static String hash(char c) {
        return String.valueOf(c).repeat(64);
    }

    private ApiSchemaDriftFindingEntity finding(ApiSchemaRevisionEntity revision, ApiSchemaDriftKind kind,
                                                ApiSchemaDriftStatus status, Instant createdAt) {
        var e = new ApiSchemaDriftFindingEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(UUID.randomUUID());
        e.setConnectorId(revision.getConnectorId());
        e.setSchemaId(revision.getSchemaId());
        e.setRevisionId(revision.getId());
        e.setKind(kind);
        e.setOperationId("op-" + kind.name());
        e.setStatus(status);
        e.setCreatedAt(createdAt);
        return e;
    }

    private ApiConnectorHealthCheckEntity check(ApiConnectorEntity connector, Instant checkedAt, boolean success) {
        var e = new ApiConnectorHealthCheckEntity();
        e.setId(UUID.randomUUID());
        e.setConnectorId(connector.getId());
        e.setCheckedAt(checkedAt);
        e.setSuccess(success);
        e.setMessage(success ? null : "HTTP 503");
        e.setDurationMs(42);
        return e;
    }

    @Test
    void newConnectorAndSchemaColumnsDefaultAndRoundTrip() {
        var connector = connector();
        var schema = schema(connector);

        var reloadedConnector = connectorRepository.findById(connector.getId()).orElseThrow();
        assertThat(reloadedConnector.getLastProbeStatus()).isEqualTo(ApiConnectorHealthStatus.UNKNOWN);
        assertThat(reloadedConnector.getConsecutiveProbeFailures()).isZero();
        assertThat(reloadedConnector.getHealthCheckIntervalMinutes()).isNull();
        assertThat(reloadedConnector.getLastProbeAt()).isNull();
        var reloadedSchema = schemaRepository.findById(schema.getId()).orElseThrow();
        assertThat(reloadedSchema.getRefreshIntervalMinutes()).isNull();
        assertThat(reloadedSchema.getCurrentRevisionId()).isNull();
        assertThat(reloadedSchema.getLastRefreshAt()).isNull();
        assertThat(reloadedSchema.getLastRefreshError()).isNull();

        for (var status : ApiConnectorHealthStatus.values()) {
            var probedAt = now();
            reloadedConnector.setHealthCheckIntervalMinutes(10);
            reloadedConnector = connectorRepository.save(reloadedConnector);
            // Probe state is updatable = false (#894): only the targeted UPDATE writes it.
            connectorRepository.updateProbeOutcome(connector.getId(), status, probedAt, 3);
            var again = connectorRepository.findById(connector.getId()).orElseThrow();
            assertThat(again.getLastProbeStatus()).isEqualTo(status);
            assertThat(again.getLastProbeAt()).isEqualTo(probedAt);
            assertThat(again.getHealthCheckIntervalMinutes()).isEqualTo(10);
            assertThat(again.getConsecutiveProbeFailures()).isEqualTo(3);
        }

        var revisionId = UUID.randomUUID();
        var refreshedAt = now();
        reloadedSchema.setRefreshIntervalMinutes(60);
        reloadedSchema.setCurrentRevisionId(revisionId);
        reloadedSchema.setLastRefreshAt(refreshedAt);
        reloadedSchema.setLastRefreshError("timeout");
        schemaRepository.save(reloadedSchema);
        var refreshed = schemaRepository.findById(schema.getId()).orElseThrow();
        assertThat(refreshed.getRefreshIntervalMinutes()).isEqualTo(60);
        assertThat(refreshed.getCurrentRevisionId()).isEqualTo(revisionId);
        assertThat(refreshed.getLastRefreshAt()).isEqualTo(refreshedAt);
        assertThat(refreshed.getLastRefreshError()).isEqualTo("timeout");
    }

    @Test
    void rowsWrittenWithoutTheNewColumnsReadBackWithDefaults() {
        var connectorId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO api_connectors (id, organization_id, name, protocol, base_url) "
                        + "VALUES (?, ?, ?, 'REST'::api_protocol, ?)",
                connectorId, UUID.randomUUID(), "legacy-" + connectorId, "https://legacy.example.com");
        var schemaId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO api_schemas (id, connector_id, schema_type, created_at) "
                + "VALUES (?, ?, 'OPENAPI'::api_schema_type, now())", schemaId, connectorId);

        var connector = connectorRepository.findById(connectorId).orElseThrow();
        assertThat(connector.getLastProbeStatus()).isEqualTo(ApiConnectorHealthStatus.UNKNOWN);
        assertThat(connector.getConsecutiveProbeFailures()).isZero();
        assertThat(connector.getHealthCheckIntervalMinutes()).isNull();
        var schema = schemaRepository.findById(schemaId).orElseThrow();
        assertThat(schema.getRefreshIntervalMinutes()).isNull();
        assertThat(schema.getCurrentRevisionId()).isNull();
    }

    @Test
    void revisionsPersistOrderAndLookUpByHash() {
        var schema = schema(connector());
        var older = revision(schema, hash('a'), now().minus(1, ChronoUnit.HOURS));
        older.setRawContent("{\"openapi\":\"3.0.0\"}");
        older.setParsedOperations("[{\"operationId\":\"ping\"}]");
        older.setOperationCount(1);
        var acceptedBy = UUID.randomUUID();
        older.setAcceptedAt(now());
        older.setAcceptedBy(acceptedBy);
        var newer = revision(schema, hash('b'), now());
        revisionRepository.saveAll(List.of(older, newer));

        assertThat(revisionRepository.findBySchemaIdOrderByFetchedAtDesc(schema.getId()))
                .extracting(ApiSchemaRevisionEntity::getId).containsExactly(newer.getId(), older.getId());
        assertThat(revisionRepository.findFirstBySchemaIdOrderByFetchedAtDesc(schema.getId()))
                .map(ApiSchemaRevisionEntity::getId).contains(newer.getId());
        var byHash = revisionRepository.findBySchemaIdAndContentHash(schema.getId(), hash('a')).orElseThrow();
        assertThat(byHash.getId()).isEqualTo(older.getId());
        assertThat(byHash.getContentHash()).hasSize(64);
        assertThat(byHash.getRawContent()).isEqualTo("{\"openapi\":\"3.0.0\"}");
        assertThat(byHash.getParsedOperations()).contains("ping");
        assertThat(byHash.getOperationCount()).isEqualTo(1);
        assertThat(byHash.getAcceptedBy()).isEqualTo(acceptedBy);
        assertThat(newer.getParsedOperations()).isEqualTo("[]");
        assertThat(revisionRepository.findBySchemaIdAndContentHash(schema.getId(), hash('c'))).isEmpty();
    }

    @Test
    void duplicateContentHashForTheSameSchemaIsRejected() {
        var schema = schema(connector());
        revisionRepository.saveAndFlush(revision(schema, hash('d'), now()));

        assertThatThrownBy(() -> revisionRepository.saveAndFlush(revision(schema, hash('d'), now())))
                .isInstanceOf(DataIntegrityViolationException.class);

        // The same hash under a different schema is fine.
        var other = schema(connector());
        revisionRepository.saveAndFlush(revision(other, hash('d'), now()));
        assertThat(revisionRepository.findBySchemaIdAndContentHash(other.getId(), hash('d'))).isPresent();
    }

    @Test
    void findingsRoundTripEveryKindAndStatus() {
        var schema = schema(connector());
        var revision = revisionRepository.save(revision(schema, hash('e'), now()));
        var base = now().minus(1, ChronoUnit.DAYS);
        var kinds = ApiSchemaDriftKind.values();
        for (int i = 0; i < kinds.length; i++) {
            findingRepository.save(finding(revision, kinds[i], ApiSchemaDriftStatus.OPEN,
                    base.plus(i, ChronoUnit.MINUTES)));
        }
        var accepted = finding(revision, ApiSchemaDriftKind.SHAPE_CHANGED, ApiSchemaDriftStatus.ACCEPTED, now());
        accepted.setDetail("{\"before\":{},\"after\":{},\"grantIds\":[]}");
        accepted.setAffectedGrantCount(2);
        var resolvedBy = UUID.randomUUID();
        accepted.setResolvedAt(now());
        accepted.setResolvedBy(resolvedBy);
        findingRepository.save(accepted);
        findingRepository.save(finding(revision, ApiSchemaDriftKind.DEPRECATED, ApiSchemaDriftStatus.DISMISSED, now()));

        var open = findingRepository.findByConnectorIdAndStatusOrderByCreatedAtDesc(schema.getConnectorId(),
                ApiSchemaDriftStatus.OPEN);
        assertThat(open).extracting(ApiSchemaDriftFindingEntity::getKind)
                .containsExactly(kinds[4], kinds[3], kinds[2], kinds[1], kinds[0]);
        assertThat(open).allSatisfy(f -> {
            assertThat(f.getDetail()).isEqualTo("{}");
            assertThat(f.getAffectedGrantCount()).isZero();
        });
        var acceptedRows = findingRepository.findBySchemaIdAndStatus(schema.getId(), ApiSchemaDriftStatus.ACCEPTED);
        assertThat(acceptedRows).singleElement().satisfies(f -> {
            assertThat(f.getAffectedGrantCount()).isEqualTo(2);
            assertThat(f.getDetail()).contains("grantIds");
            assertThat(f.getResolvedBy()).isEqualTo(resolvedBy);
            assertThat(f.getResolvedAt()).isNotNull();
        });
        assertThat(findingRepository.findBySchemaIdAndStatus(schema.getId(), ApiSchemaDriftStatus.DISMISSED))
                .singleElement().extracting(ApiSchemaDriftFindingEntity::getKind)
                .isEqualTo(ApiSchemaDriftKind.DEPRECATED);
    }

    @Test
    void staleFindingSaveIsRejectedByTheVersionColumn() {
        var schema = schema(connector());
        var revision = revisionRepository.save(revision(schema, hash('h'), now()));
        var saved = findingRepository.save(
                finding(revision, ApiSchemaDriftKind.SHAPE_CHANGED, ApiSchemaDriftStatus.OPEN, now()));
        var first = findingRepository.findById(saved.getId()).orElseThrow();
        var stale = findingRepository.findById(saved.getId()).orElseThrow();

        first.setStatus(ApiSchemaDriftStatus.ACCEPTED);
        findingRepository.save(first);
        stale.setStatus(ApiSchemaDriftStatus.DISMISSED);

        assertThatThrownBy(() -> findingRepository.save(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(findingRepository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(ApiSchemaDriftStatus.ACCEPTED);
    }

    @Test
    void deletingTheSchemaCascadesToRevisionsAndFindings() {
        var schema = schema(connector());
        var revision = revisionRepository.save(revision(schema, hash('f'), now()));
        var finding = findingRepository.save(
                finding(revision, ApiSchemaDriftKind.OPERATION_ADDED, ApiSchemaDriftStatus.OPEN, now()));

        schemaRepository.deleteById(schema.getId());

        assertThat(revisionRepository.findById(revision.getId())).isEmpty();
        assertThat(findingRepository.findById(finding.getId())).isEmpty();
    }

    @Test
    void deletingARevisionCascadesToItsFindings() {
        var schema = schema(connector());
        var revision = revisionRepository.save(revision(schema, hash('g'), now()));
        var finding = findingRepository.save(
                finding(revision, ApiSchemaDriftKind.OPERATION_REMOVED, ApiSchemaDriftStatus.OPEN, now()));

        revisionRepository.deleteById(revision.getId());

        assertThat(findingRepository.findById(finding.getId())).isEmpty();
        assertThat(schemaRepository.findById(schema.getId())).isPresent();
    }

    @Test
    void healthChecksListNewestFirstPruneAndCascadeWithTheConnector() {
        var connector = connector();
        var base = now().minus(30, ChronoUnit.DAYS);
        for (int i = 0; i < 22; i++) {
            healthCheckRepository.save(check(connector, base.plus(i, ChronoUnit.HOURS), i % 2 == 0));
        }

        var latest = healthCheckRepository.findTop20ByConnectorIdOrderByCheckedAtDesc(connector.getId());
        assertThat(latest).hasSize(20);
        assertThat(latest.get(0).getCheckedAt()).isEqualTo(base.plus(21, ChronoUnit.HOURS));
        assertThat(latest.get(0).isSuccess()).isFalse();
        assertThat(latest.get(0).getMessage()).isEqualTo("HTTP 503");
        assertThat(latest.get(0).getDurationMs()).isEqualTo(42);

        var pruned = healthCheckRepository.deleteByCheckedAtBefore(base.plus(2, ChronoUnit.HOURS));
        assertThat(pruned).isGreaterThanOrEqualTo(2);
        assertThat(healthCheckRepository.findTop20ByConnectorIdOrderByCheckedAtDesc(connector.getId()))
                .hasSize(20)
                .allSatisfy(c -> assertThat(c.getCheckedAt()).isAfterOrEqualTo(base.plus(2, ChronoUnit.HOURS)));

        connectorRepository.deleteById(connector.getId());

        assertThat(healthCheckRepository.findTop20ByConnectorIdOrderByCheckedAtDesc(connector.getId())).isEmpty();
    }
}
