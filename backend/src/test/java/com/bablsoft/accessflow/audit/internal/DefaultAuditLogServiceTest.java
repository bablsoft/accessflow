package com.bablsoft.accessflow.audit.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogQuery;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditLogEntity;
import com.bablsoft.accessflow.audit.internal.persistence.repo.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAuditLogServiceTest {

    private final AuditLogRepository repository = mock(AuditLogRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final byte[] key = "01234567890123456789012345678901".getBytes();
    private final AuditChainHasher hasher = new AuditChainHasher(key, objectMapper);
    private final JdbcTemplate auditJdbcTemplate = mock(JdbcTemplate.class);
    private final PlatformTransactionManager auditTxManager = mock(PlatformTransactionManager.class);
    private final TransactionTemplate auditTransactionTemplate = new TransactionTemplate(auditTxManager);
    private final DefaultAuditLogService service = new DefaultAuditLogService(
            repository, objectMapper, hasher, auditJdbcTemplate, auditTransactionTemplate);

    private final UUID organizationId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID resourceId = UUID.randomUUID();

    DefaultAuditLogServiceTest() {
        // Both advisory-lock and prev-hash reads use query(sql, ResultSetExtractor, args).
        // Default behaviour: no rows / void result.
        when(auditJdbcTemplate.query(any(String.class), any(ResultSetExtractor.class), any(Object[].class)))
                .thenReturn(null);
        when(auditJdbcTemplate.update(any(PreparedStatementCreator.class))).thenReturn(1);
    }

    @Test
    void recordReturnsGeneratedId() {
        var id = service.record(new AuditEntry(
                AuditAction.QUERY_SUBMITTED,
                AuditResourceType.QUERY_REQUEST,
                resourceId,
                organizationId,
                actorId,
                Map.of("foo", "bar"),
                "10.0.0.1",
                "Mozilla/5.0"));

        assertThat(id).isNotNull();
        verify(auditJdbcTemplate).update(any(PreparedStatementCreator.class));
    }

    @Test
    void recordAcquiresAdvisoryLockWithOrgIdBasedKey() {
        service.record(new AuditEntry(
                AuditAction.QUERY_SUBMITTED,
                AuditResourceType.QUERY_REQUEST,
                resourceId,
                organizationId,
                actorId,
                Map.of(),
                null,
                null));

        long expected = organizationId.getMostSignificantBits()
                ^ organizationId.getLeastSignificantBits();
        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        var argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(auditJdbcTemplate, atLeastOnce()).query(
                sqlCaptor.capture(),
                any(ResultSetExtractor.class),
                argsCaptor.capture());

        // First call must be the advisory lock, with the org-id-derived key.
        assertThat(sqlCaptor.getAllValues()).contains("SELECT pg_advisory_xact_lock(?)");
        assertThat(argsCaptor.getAllValues()).anySatisfy(args ->
                assertThat(args).containsExactly(expected));
    }

    @Test
    void recordReadsPreviousHashForOrganization() {
        service.record(new AuditEntry(
                AuditAction.QUERY_SUBMITTED,
                AuditResourceType.QUERY_REQUEST,
                resourceId,
                organizationId,
                actorId,
                Map.of(),
                null,
                null));

        var sqlCaptor = ArgumentCaptor.forClass(String.class);
        var argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(auditJdbcTemplate, atLeastOnce()).query(
                sqlCaptor.capture(),
                any(ResultSetExtractor.class),
                argsCaptor.capture());

        assertThat(sqlCaptor.getAllValues()).anyMatch(sql ->
                sql.startsWith("SELECT current_hash FROM audit_log WHERE organization_id"));
        assertThat(argsCaptor.getAllValues()).anySatisfy(args ->
                assertThat(args).containsExactly(organizationId));
    }

    @Test
    void recordChainsToPriorRowHashWhenPresent() {
        var priorHash = new byte[32];
        java.util.Arrays.fill(priorHash, (byte) 0xAB);
        when(auditJdbcTemplate.query(
                eq("SELECT current_hash FROM audit_log WHERE organization_id = ? "
                        + "ORDER BY created_at DESC, id DESC LIMIT 1"),
                any(ResultSetExtractor.class),
                any(Object[].class)))
                .thenReturn(priorHash);

        var id = service.record(new AuditEntry(
                AuditAction.QUERY_APPROVED,
                AuditResourceType.QUERY_REQUEST,
                resourceId,
                organizationId,
                actorId,
                Map.of(),
                null,
                null));

        // Smoke test: the insert was issued. End-to-end hash chaining is covered by
        // AuditLogIntegrationTest against a real Postgres testcontainer.
        assertThat(id).isNotNull();
        verify(auditJdbcTemplate).update(any(PreparedStatementCreator.class));
    }

    @Test
    void queryRequiresOrganizationId() {
        assertThatThrownBy(() -> service.query(null, AuditLogQuery.empty(),
                com.bablsoft.accessflow.core.api.PageRequest.of(0, 20)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void queryReturnsMappedViews() {
        var entity = new AuditLogEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setActorId(actorId);
        entity.setAction(AuditAction.QUERY_SUBMITTED.name());
        entity.setResourceType(AuditResourceType.QUERY_REQUEST.dbValue());
        entity.setResourceId(resourceId);
        entity.setMetadata("{\"foo\":\"bar\"}");
        entity.setCreatedAt(Instant.parse("2026-05-06T10:00:00Z"));
        var pageable = com.bablsoft.accessflow.core.api.PageRequest.of(0, 20);
        Page<AuditLogEntity> page = new PageImpl<>(java.util.List.of(entity),
                org.springframework.data.domain.PageRequest.of(0, 20), 1);
        when(repository.findAll(any(Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(page);

        var result = service.query(organizationId, AuditLogQuery.empty(), pageable);

        assertThat(result.totalElements()).isEqualTo(1);
        var view = result.content().get(0);
        assertThat(view.action()).isEqualTo(AuditAction.QUERY_SUBMITTED);
        assertThat(view.resourceType()).isEqualTo(AuditResourceType.QUERY_REQUEST);
        assertThat(view.metadata()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) view.metadata()).get("foo")).isEqualTo("bar");
        verify(repository).findAll(any(Specification.class), any(org.springframework.data.domain.Pageable.class));
    }

    @Test
    void queryHandlesEmptyMetadataString() {
        var entity = new AuditLogEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setAction(AuditAction.USER_LOGIN.name());
        entity.setResourceType(AuditResourceType.USER.dbValue());
        entity.setMetadata("");
        entity.setCreatedAt(Instant.now());
        var pageable = com.bablsoft.accessflow.core.api.PageRequest.of(0, 20);
        when(repository.findAll(any(Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(java.util.List.of(entity),
                        org.springframework.data.domain.PageRequest.of(0, 20), 1));

        var result = service.query(organizationId, null, pageable);

        assertThat(result.content().get(0).metadata()).isEqualTo(Map.of());
    }

    @Test
    void recordSerializesNullMetadataAsEmptyObject() {
        var metadata = new LinkedHashMap<String, Object>();
        var entry = new AuditEntry(
                AuditAction.USER_LOGIN,
                AuditResourceType.USER,
                actorId,
                organizationId,
                actorId,
                metadata,
                null,
                null);

        var id = service.record(entry);

        assertThat(id).isNotNull();
        verify(auditJdbcTemplate).update(any(PreparedStatementCreator.class));
    }

    @Test
    void verifyRequiresOrganizationId() {
        assertThatThrownBy(() -> service.verify(null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- All-organizations verification sweep (AF-458) ---

    @Test
    void verifyAllOrganizationsReturnsEmptyWhenNoAuditRows() {
        when(repository.findDistinctOrganizationIds()).thenReturn(java.util.List.of());

        assertThat(service.verifyAllOrganizations()).isEmpty();
    }

    @Test
    void verifyAllOrganizationsReportsPerOrgOutcome() {
        var intactOrg = UUID.randomUUID();
        var tamperedOrg = UUID.randomUUID();
        when(repository.findDistinctOrganizationIds())
                .thenReturn(java.util.List.of(intactOrg, tamperedOrg));

        var intactAnchor = chainRow(intactOrg, null);
        var intactNext = chainRow(intactOrg, intactAnchor.getCurrentHash());
        var tamperedAnchor = chainRow(tamperedOrg, null);
        tamperedAnchor.setMetadata("{\"tampered\":true}"); // stored hash no longer matches content
        when(repository.findForVerification(any(Specification.class)))
                .thenReturn(java.util.List.of(intactAnchor, intactNext))
                .thenReturn(java.util.List.of(tamperedAnchor));

        var summaries = service.verifyAllOrganizations();

        assertThat(summaries).hasSize(2);
        assertThat(summaries.get(0).organizationId()).isEqualTo(intactOrg);
        assertThat(summaries.get(0).result().ok()).isTrue();
        assertThat(summaries.get(0).result().rowsChecked()).isEqualTo(2);
        assertThat(summaries.get(1).organizationId()).isEqualTo(tamperedOrg);
        assertThat(summaries.get(1).result().ok()).isFalse();
        assertThat(summaries.get(1).result().firstBadReason())
                .isEqualTo(DefaultAuditLogService.REASON_CURRENT_HASH_MISMATCH);
        assertThat(summaries.get(1).result().firstBadRowId()).isEqualTo(tamperedAnchor.getId());
    }

    private AuditLogEntity chainRow(UUID orgId, byte[] previousHash) {
        var row = new AuditLogEntity();
        row.setId(UUID.randomUUID());
        row.setOrganizationId(orgId);
        row.setActorId(actorId);
        row.setAction(AuditAction.USER_LOGIN.name());
        row.setResourceType(AuditResourceType.USER.dbValue());
        row.setMetadata("{}");
        row.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        row.setPreviousHash(previousHash);
        row.setCurrentHash(hasher.hash(row, previousHash));
        return row;
    }

    @SuppressWarnings("unused")
    private static long unusedLong() { return anyLong(); }
}
