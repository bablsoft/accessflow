package com.bablsoft.accessflow.audit.internal;

import com.bablsoft.accessflow.audit.api.AuditLogQuery;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditLogEntity;
import com.bablsoft.accessflow.audit.internal.persistence.repo.AuditLogRepository;
import com.bablsoft.accessflow.core.api.UserAdminService;
import com.bablsoft.accessflow.core.api.UserView;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Streams the audit log as RFC 4180 CSV. Lives in {@code audit/internal} so the controller can
 * stay a thin pass-through (CLAUDE.md controller-layering rule). Bytes are written directly to
 * the response {@link OutputStream} via {@code StreamingResponseBody}; the full result is never
 * buffered in memory.
 */
@Service
@RequiredArgsConstructor
public class AuditLogCsvService {

    public static final int MAX_EXPORT_ROWS = 50_000;
    static final int STREAM_PAGE_SIZE = 500;

    static final List<String> HEADER = List.of(
            "timestamp", "organization_id", "actor_email", "action", "resource_type",
            "resource_id", "ip_address", "user_agent", "current_hash", "previous_hash",
            "metadata_json");

    private static final DateTimeFormatter FILENAME_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private static final HexFormat HEX = HexFormat.of();

    private final AuditLogRepository repository;
    private final UserAdminService userAdminService;

    @Transactional(readOnly = true)
    public long count(UUID organizationId, AuditLogQuery filter) {
        var query = filter == null ? AuditLogQuery.empty() : filter;
        return repository.count(AuditLogSpecifications.forQuery(organizationId, query));
    }

    @Transactional(readOnly = true)
    public void streamCsv(UUID organizationId, AuditLogQuery filter, OutputStream out) {
        var query = filter == null ? AuditLogQuery.empty() : filter;
        var spec = AuditLogSpecifications.forQuery(organizationId, query);
        var writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        try {
            CsvWriter.writeRow(writer, HEADER);
            int emitted = 0;
            int pageIndex = 0;
            while (emitted < MAX_EXPORT_ROWS) {
                int remaining = MAX_EXPORT_ROWS - emitted;
                int pageSize = Math.min(STREAM_PAGE_SIZE, remaining);
                var page = repository.findAll(spec, PageRequest.of(pageIndex, pageSize));
                var actorEmails = resolveActorEmails(organizationId, page.getContent());
                for (var entity : page.getContent()) {
                    CsvWriter.writeRow(writer, toRow(entity, actorEmails));
                    emitted++;
                    if (emitted >= MAX_EXPORT_ROWS) {
                        break;
                    }
                }
                writer.flush();
                if (!page.hasNext()) {
                    return;
                }
                pageIndex++;
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public String filename(Instant now) {
        return "audit-log-" + FILENAME_TIMESTAMP.format(now) + ".csv";
    }

    private Map<UUID, UserView> resolveActorEmails(UUID organizationId, List<AuditLogEntity> rows) {
        Set<UUID> ids = new HashSet<>();
        for (var entity : rows) {
            if (entity.getActorId() != null) {
                ids.add(entity.getActorId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userAdminService.findByIds(organizationId, ids);
    }

    private static List<String> toRow(AuditLogEntity entity, Map<UUID, UserView> actorEmails) {
        UserView actor = entity.getActorId() == null ? null : actorEmails.get(entity.getActorId());
        return List.of(
                stringOf(entity.getCreatedAt()),
                stringOf(entity.getOrganizationId()),
                actor == null ? "" : stringOf(actor.email()),
                stringOf(entity.getAction()),
                stringOf(entity.getResourceType()),
                stringOf(entity.getResourceId()),
                stringOf(entity.getIpAddress()),
                stringOf(entity.getUserAgent()),
                hexOf(entity.getCurrentHash()),
                hexOf(entity.getPreviousHash()),
                stringOf(entity.getMetadata()));
    }

    private static String stringOf(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String hexOf(byte[] bytes) {
        return bytes == null ? "" : HEX.formatHex(bytes);
    }
}
