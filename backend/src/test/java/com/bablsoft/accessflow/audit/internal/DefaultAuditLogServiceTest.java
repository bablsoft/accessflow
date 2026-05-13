package com.bablsoft.accessflow.audit.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogQuery;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditLogEntity;
import com.bablsoft.accessflow.audit.internal.persistence.repo.AuditLogRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.jpa.domain.Specification;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAuditLogServiceTest {

    private final AuditLogRepository repository = mock(AuditLogRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final byte[] key = "01234567890123456789012345678901".getBytes();
    private final AuditChainHasher hasher = new AuditChainHasher(key, objectMapper);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final Query advisoryLockQuery = mock(Query.class);
    private final DefaultAuditLogService service =
            new DefaultAuditLogService(repository, objectMapper, hasher, entityManager);

    private final UUID organizationId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();
    private final UUID resourceId = UUID.randomUUID();

    DefaultAuditLogServiceTest() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(advisoryLockQuery);
        when(advisoryLockQuery.setParameter(eq(1), any())).thenReturn(advisoryLockQuery);
        when(advisoryLockQuery.getSingleResult()).thenReturn(1);
    }

    @Test
    void recordPersistsRowWithSerializedMetadata() {
        when(repository.findTopByOrganizationIdOrderByCreatedAtDescIdDesc(organizationId))
                .thenReturn(Optional.empty());
        var captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        when(repository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));
        var entry = new AuditEntry(
                AuditAction.QUERY_SUBMITTED,
                AuditResourceType.QUERY_REQUEST,
                resourceId,
                organizationId,
                actorId,
                Map.of("foo", "bar", "n", 1),
                "10.0.0.1",
                "Mozilla/5.0");

        var id = service.record(entry);

        var saved = captor.getValue();
        assertThat(id).isNotNull();
        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getAction()).isEqualTo("QUERY_SUBMITTED");
        assertThat(saved.getResourceType()).isEqualTo("query_request");
        assertThat(saved.getResourceId()).isEqualTo(resourceId);
        assertThat(saved.getOrganizationId()).isEqualTo(organizationId);
        assertThat(saved.getActorId()).isEqualTo(actorId);
        assertThat(saved.getIpAddress()).isEqualTo("10.0.0.1");
        assertThat(saved.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getMetadata()).contains("\"foo\":\"bar\"").contains("\"n\":1");
        assertThat(saved.getPreviousHash()).isNull();
        assertThat(saved.getCurrentHash()).hasSize(32);
    }

    @Test
    void recordChainsToPriorRowsCurrentHash() {
        var prior = new AuditLogEntity();
        prior.setId(UUID.randomUUID());
        prior.setOrganizationId(organizationId);
        var priorHash = new byte[32];
        java.util.Arrays.fill(priorHash, (byte) 0xAB);
        prior.setCurrentHash(priorHash);
        when(repository.findTopByOrganizationIdOrderByCreatedAtDescIdDesc(organizationId))
                .thenReturn(Optional.of(prior));
        var captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        when(repository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        service.record(new AuditEntry(
                AuditAction.QUERY_APPROVED,
                AuditResourceType.QUERY_REQUEST,
                resourceId,
                organizationId,
                actorId,
                Map.of(),
                null,
                null));

        var saved = captor.getValue();
        assertThat(saved.getPreviousHash()).isEqualTo(priorHash);
        assertThat(saved.getCurrentHash()).isNotNull().isNotEqualTo(priorHash);
    }

    @Test
    void recordAcquiresAdvisoryLockOncePerCall() {
        when(repository.findTopByOrganizationIdOrderByCreatedAtDescIdDesc(organizationId))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.record(new AuditEntry(
                AuditAction.QUERY_SUBMITTED,
                AuditResourceType.QUERY_REQUEST,
                resourceId,
                organizationId,
                actorId,
                Map.of(),
                null,
                null));

        verify(entityManager).createNativeQuery("SELECT pg_advisory_xact_lock(?1)");
        long expected = organizationId.getMostSignificantBits()
                ^ organizationId.getLeastSignificantBits();
        verify(advisoryLockQuery).setParameter(1, expected);
        verify(advisoryLockQuery).getSingleResult();
    }

    @Test
    void recordHandlesNullActorAndEmptyMetadata() {
        when(repository.findTopByOrganizationIdOrderByCreatedAtDescIdDesc(organizationId))
                .thenReturn(Optional.empty());
        var captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        when(repository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));
        var entry = new AuditEntry(
                AuditAction.QUERY_APPROVED,
                AuditResourceType.QUERY_REQUEST,
                resourceId,
                organizationId,
                null,
                Map.of(),
                null,
                null);

        service.record(entry);

        var saved = captor.getValue();
        assertThat(saved.getActorId()).isNull();
        assertThat(saved.getMetadata()).isEqualTo("{}");
        assertThat(saved.getIpAddress()).isNull();
        assertThat(saved.getUserAgent()).isNull();
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
        when(repository.findTopByOrganizationIdOrderByCreatedAtDescIdDesc(organizationId))
                .thenReturn(Optional.empty());
        var captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        when(repository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));
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

        service.record(entry);

        assertThat(captor.getValue().getMetadata()).isEqualTo("{}");
    }

    @Test
    void verifyRequiresOrganizationId() {
        assertThatThrownBy(() -> service.verify(null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
