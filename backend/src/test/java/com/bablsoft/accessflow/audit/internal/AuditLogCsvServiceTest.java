package com.bablsoft.accessflow.audit.internal;

import com.bablsoft.accessflow.audit.api.AuditLogQuery;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditLogEntity;
import com.bablsoft.accessflow.audit.internal.persistence.repo.AuditLogRepository;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserAdminService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLogCsvServiceTest {

    private static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock AuditLogRepository repository;
    @Mock UserAdminService userAdminService;

    @InjectMocks AuditLogCsvService service;

    @Test
    void filenameUsesUtcTimestamp() {
        // 2026-05-27T12:34:56Z
        var instant = Instant.parse("2026-05-27T12:34:56Z");
        assertThat(service.filename(instant)).isEqualTo("audit-log-20260527-123456.csv");
    }

    @Test
    void countDelegatesToRepository() {
        when(repository.count(any(Specification.class))).thenReturn(42L);

        assertThat(service.count(ORG, AuditLogQuery.empty())).isEqualTo(42L);

        verify(repository).count(any(Specification.class));
    }

    @Test
    void countToleratesNullFilter() {
        when(repository.count(any(Specification.class))).thenReturn(0L);

        assertThat(service.count(ORG, null)).isZero();
    }

    @Test
    void streamCsvWritesHeaderEvenWhenEmpty() {
        when(repository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(emptyPage());

        var out = new ByteArrayOutputStream();
        service.streamCsv(ORG, AuditLogQuery.empty(), out);

        var lines = out.toString().split("\r\n", -1);
        assertThat(lines[0]).isEqualTo("timestamp,organization_id,actor_email,action,"
                + "resource_type,resource_id,ip_address,user_agent,current_hash,"
                + "previous_hash,metadata_json");
        // header + trailing empty after final \r\n
        assertThat(lines).hasSize(2);
        verifyNoInteractions(userAdminService);
    }

    @Test
    void streamCsvRendersAllColumnsAndEscapesMetadata() {
        var actorId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        var resourceId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        var row = entity(actorId, "USER_LOGIN", "user", resourceId,
                "{\"reason\":\"comma, and \\\"quote\\\"\"}",
                "10.0.0.5", "Mozilla/5.0",
                new byte[] {0x01, 0x02, (byte) 0xFE},
                new byte[] {0x10, 0x20});
        when(repository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(row)));
        when(userAdminService.findByIds(eq(ORG), anyCollection()))
                .thenReturn(Map.of(actorId, userView(actorId, "alice@example.com")));

        var out = new ByteArrayOutputStream();
        service.streamCsv(ORG, AuditLogQuery.empty(), out);

        var lines = out.toString().split("\r\n", -1);
        assertThat(lines).hasSizeGreaterThanOrEqualTo(2);
        // Row 1 (after header) — fields with embedded commas / quotes must be RFC 4180 quoted.
        var data = lines[1];
        assertThat(data).startsWith(row.getCreatedAt().toString() + ",");
        assertThat(data).contains("," + ORG + ",alice@example.com,USER_LOGIN,user,"
                + resourceId + ",10.0.0.5,Mozilla/5.0,0102fe,1020,");
        // metadata is RFC 4180 quoted because it contains commas and quotes
        assertThat(data).endsWith(",\"{\"\"reason\"\":\"\"comma, and \\\"\"quote\\\"\"\"\"}\"");
    }

    @Test
    void streamCsvLeavesActorEmailBlankForSystemRows() {
        var row = entity(null, "QUERY_AI_ANALYZED", "query_request", UUID.randomUUID(),
                "{}", null, null, null, null);
        when(repository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(row)));

        var out = new ByteArrayOutputStream();
        service.streamCsv(ORG, AuditLogQuery.empty(), out);

        var data = out.toString().split("\r\n", -1)[1];
        // 3rd column is actor_email and must be empty (",,")
        var cols = data.split(",", -1);
        assertThat(cols[2]).isEmpty();
        // current_hash and previous_hash are also empty for pre-V26 rows
        assertThat(cols[8]).isEmpty();
        assertThat(cols[9]).isEmpty();
        verify(userAdminService, never()).findByIds(any(), any());
    }

    @Test
    void streamCsvIteratesPagesAndStopsWhenRepositoryHasNoMore() {
        var pageOne = pageOf(entity(null, "USER_LOGIN", "user", UUID.randomUUID(),
                "{}", null, null, null, null));
        var pageTwo = pageOf(entity(null, "USER_CREATED", "user", UUID.randomUUID(),
                "{}", null, null, null, null));
        // Force hasNext()=true on the first page by claiming total=2 with size=1
        // (PageImpl computes totalPages from total/size).
        when(repository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(pageOne, PageRequest.of(0, 1), 2))
                .thenReturn(new PageImpl<>(pageTwo, PageRequest.of(1, 1), 2));

        var out = new ByteArrayOutputStream();
        service.streamCsv(ORG, AuditLogQuery.empty(), out);

        var captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(repository, times(2)).findAll(any(Specification.class), captor.capture());
        assertThat(captor.getAllValues()).extracting(PageRequest::getPageNumber)
                .containsExactly(0, 1);
        var lines = out.toString().split("\r\n", -1);
        // header + 2 data rows + trailing empty
        assertThat(lines).hasSize(4);
        assertThat(lines[1]).contains("USER_LOGIN");
        assertThat(lines[2]).contains("USER_CREATED");
    }

    @Test
    void streamCsvCapsAtMaxExportRows() {
        // Page returns more rows than the remaining cap; the service must stop emitting and
        // never request a follow-up page.
        var bigPage = new java.util.ArrayList<AuditLogEntity>();
        for (int i = 0; i < AuditLogCsvService.MAX_EXPORT_ROWS + 100; i++) {
            bigPage.add(entity(null, "USER_LOGIN", "user", UUID.randomUUID(),
                    "{}", null, null, null, null));
        }
        when(repository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(bigPage));

        var out = new ByteArrayOutputStream();
        service.streamCsv(ORG, AuditLogQuery.empty(), out);

        var lines = out.toString().split("\r\n", -1);
        // header + MAX_EXPORT_ROWS data rows + trailing empty after the last \r\n
        assertThat(lines).hasSize(AuditLogCsvService.MAX_EXPORT_ROWS + 2);
        verify(repository, times(1)).findAll(any(Specification.class), any(PageRequest.class));
    }

    private static List<AuditLogEntity> pageOf(AuditLogEntity entity) {
        return List.of(entity);
    }

    private static Page<AuditLogEntity> emptyPage() {
        return new PageImpl<>(List.of());
    }

    private static AuditLogEntity entity(UUID actorId, String action, String resourceType,
                                         UUID resourceId, String metadataJson, String ip,
                                         String ua, byte[] currentHash, byte[] previousHash) {
        var e = new AuditLogEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(ORG);
        e.setActorId(actorId);
        e.setAction(action);
        e.setResourceType(resourceType);
        e.setResourceId(resourceId);
        e.setMetadata(metadataJson);
        e.setIpAddress(ip);
        e.setUserAgent(ua);
        e.setCreatedAt(Instant.parse("2026-05-27T10:30:00Z"));
        e.setCurrentHash(currentHash);
        e.setPreviousHash(previousHash);
        return e;
    }

    private static UserView userView(UUID id, String email) {
        return new UserView(id, email, email, UserRoleType.ANALYST, ORG, true,
                AuthProviderType.LOCAL, "hash", null, "en", false, Instant.now());
    }
}
