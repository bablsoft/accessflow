package com.bablsoft.accessflow.compliance.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.compliance.api.ExportDecision;
import com.bablsoft.accessflow.compliance.api.ResultExportGovernanceService;
import com.bablsoft.accessflow.compliance.events.SensitiveResultExportedEvent;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.DataClassificationQueryService;
import com.bablsoft.accessflow.core.api.DataClassificationTagView;
import com.bablsoft.accessflow.core.api.ExportPolicyMode;
import com.bablsoft.accessflow.core.api.ExportPolicyResolutionService;
import com.bablsoft.accessflow.core.api.ExportPolicyView;
import com.bablsoft.accessflow.core.api.QueryResultPersistenceService;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotService;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotView;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Combines an exporter's applicable export policies with the classifications actually present in
 * the persisted result (#626). Classification presence is computed at export time: a table-level
 * tag on a referenced table classifies every returned column of that table; a column-level tag
 * matches returned columns by name, case-insensitively (best-effort for document engines whose
 * result columns are field unions). Table matching reuses {@link TableNameNormalizer} so tags and
 * {@code referenced_tables} fold identically here and in the classified-access report.
 */
@Service
@RequiredArgsConstructor
class DefaultResultExportGovernanceService implements ResultExportGovernanceService {

    private static final Logger log =
            LoggerFactory.getLogger(DefaultResultExportGovernanceService.class);
    private static final TypeReference<List<Map<String, Object>>> COLUMNS_TYPE =
            new TypeReference<>() {};

    private final ExportPolicyResolutionService exportPolicyResolutionService;
    private final DataClassificationQueryService dataClassificationQueryService;
    private final QuerySnapshotService querySnapshotService;
    private final QueryResultPersistenceService queryResultPersistenceService;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Override
    public ExportDecision decide(UUID organizationId, UUID queryRequestId, UUID requesterUserId) {
        var snapshot = querySnapshotService.find(queryRequestId, organizationId).orElse(null);
        if (snapshot == null) {
            // Fail closed: without the snapshot neither the datasource's policies nor the
            // classifications can be resolved, so an egress decision cannot be verified. The
            // only production caller without a prior snapshot check is the email-attachment
            // path, where a deny merely suppresses the attachment (the mail still delivers).
            log.warn("Denying result export for query {} — no execution snapshot to resolve "
                    + "policies against", queryRequestId);
            return new ExportDecision(false, ExportPolicyMode.DENY_CLASSIFIED, null, false,
                    List.of(), List.of());
        }
        var columnNames = resultColumnNames(queryRequestId);
        return decide(snapshot, columnNames, requesterUserId);
    }

    /**
     * The shared decision computation; package-private so {@code DefaultResultExportService} can
     * reuse an already-loaded snapshot without a second lookup.
     */
    ExportDecision decide(QuerySnapshotView snapshot, List<String> resultColumnNames,
                          UUID requesterUserId) {
        var classificationsPresent = classificationsPresent(snapshot, resultColumnNames);
        var policies = exportPolicyResolutionService.resolveApplicable(
                snapshot.organizationId(), snapshot.datasourceId(), requesterUserId);

        var participating = new ArrayList<ExportPolicyView>();
        for (var policy : policies) {
            if (policy.mode() == ExportPolicyMode.DENY_CLASSIFIED
                    && !denyMatches(policy, classificationsPresent)) {
                continue; // no matching classified column — the deny drops out
            }
            participating.add(policy);
        }

        var effectiveMode = ExportPolicyMode.ALLOW;
        Integer rowCap = null;
        for (var policy : participating) {
            if (policy.mode().ordinal() > effectiveMode.ordinal()) {
                effectiveMode = policy.mode();
            }
            if (policy.mode() == ExportPolicyMode.ROW_CAP && policy.rowCap() != null) {
                rowCap = rowCap == null ? policy.rowCap() : Math.min(rowCap, policy.rowCap());
            }
        }

        boolean allowed = effectiveMode != ExportPolicyMode.DENY_CLASSIFIED;
        boolean watermark = effectiveMode == ExportPolicyMode.WATERMARK
                || effectiveMode == ExportPolicyMode.ROW_CAP;
        return new ExportDecision(
                allowed,
                effectiveMode,
                effectiveMode == ExportPolicyMode.ROW_CAP ? rowCap : null,
                watermark,
                participating.stream().map(ExportPolicyView::id).toList(),
                classificationsPresent);
    }

    @Override
    public void recordAttachmentExport(UUID organizationId, UUID queryRequestId,
                                       UUID recipientUserId, String recipientEmail,
                                       ExportDecision decision, long rowCount, boolean truncated) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("trigger", "email_attachment");
        metadata.put("format", "CSV");
        metadata.put("row_count", rowCount);
        metadata.put("truncated", truncated);
        metadata.put("watermarked", decision.watermark());
        metadata.put("policy_ids", decision.policyIds().stream().map(UUID::toString).toList());
        metadata.put("classifications_present",
                decision.classificationsPresent().stream().map(Enum::name).toList());
        try {
            // Best-effort: the mail is already gone, so a failed audit write must not throw out
            // of the notification pipeline (documented carve-out on AuditLogService callers).
            auditLogService.record(new AuditEntry(
                    AuditAction.RESULT_EXPORTED,
                    AuditResourceType.QUERY_REQUEST,
                    queryRequestId,
                    organizationId,
                    recipientUserId,
                    metadata,
                    null,
                    null));
        } catch (RuntimeException ex) {
            log.error("RESULT_EXPORTED audit write failed for email attachment on query {}",
                    queryRequestId, ex);
        }
        publishIfSensitive(organizationId, queryRequestId, recipientUserId, recipientEmail,
                "CSV", rowCount, decision, "email_attachment");
    }

    /** Publishes the sensitive-export event when the export carried classified columns. */
    void publishIfSensitive(UUID organizationId, UUID queryRequestId, UUID exporterUserId,
                            String exporterEmail, String format, long rowCount,
                            ExportDecision decision, String trigger) {
        if (decision.classificationsPresent().isEmpty()) {
            return;
        }
        var snapshot = querySnapshotService.find(queryRequestId, organizationId).orElse(null);
        eventPublisher.publishEvent(new SensitiveResultExportedEvent(
                organizationId,
                queryRequestId,
                snapshot != null ? snapshot.datasourceId() : null,
                exporterUserId,
                exporterEmail,
                format,
                rowCount,
                decision.watermark(),
                decision.classificationsPresent(),
                trigger));
    }

    private boolean denyMatches(ExportPolicyView policy,
                                List<DataClassification> classificationsPresent) {
        if (classificationsPresent.isEmpty()) {
            return false;
        }
        if (policy.denyClassifications().isEmpty()) {
            return true; // empty = deny on any classification
        }
        for (var denied : policy.denyClassifications()) {
            if (classificationsPresent.contains(denied)) {
                return true;
            }
        }
        return false;
    }

    private List<DataClassification> classificationsPresent(QuerySnapshotView snapshot,
                                                            List<String> resultColumnNames) {
        var tags = dataClassificationQueryService.findByDatasource(snapshot.datasourceId(),
                snapshot.organizationId());
        if (tags.isEmpty()) {
            return List.of();
        }
        var lowerColumns = resultColumnNames.stream()
                .map(c -> c.toLowerCase(Locale.ROOT))
                .toList();
        var present = EnumSet.noneOf(DataClassification.class);
        for (var referenced : snapshot.referencedTables()) {
            var refNormalized = TableNameNormalizer.normalize(referenced);
            for (var tag : tags) {
                if (!TableNameNormalizer.matches(refNormalized,
                        TableNameNormalizer.normalize(tag.tableName()))) {
                    continue;
                }
                if (matchesColumns(tag, lowerColumns)) {
                    present.add(tag.classification());
                }
            }
        }
        return List.copyOf(present);
    }

    private static boolean matchesColumns(DataClassificationTagView tag, List<String> lowerColumns) {
        if (tag.columnName() == null || tag.columnName().isBlank()) {
            return true; // table-level tag classifies every returned column of the table
        }
        var tagged = tag.columnName().trim().toLowerCase(Locale.ROOT);
        return lowerColumns.contains(tagged);
    }

    /** Column names from the persisted result snapshot; empty when no result is stored. */
    List<String> resultColumnNames(UUID queryRequestId) {
        var result = queryResultPersistenceService.find(queryRequestId).orElse(null);
        if (result == null || result.columnsJson() == null) {
            return List.of();
        }
        try {
            var columns = objectMapper.readValue(result.columnsJson(), COLUMNS_TYPE);
            var names = new ArrayList<String>(columns.size());
            for (var column : columns) {
                var name = column.get("name");
                if (name != null) {
                    names.add(String.valueOf(name));
                }
            }
            return names;
        } catch (RuntimeException ex) {
            log.warn("Unparseable result columns for query {}", queryRequestId, ex);
            return List.of();
        }
    }
}
