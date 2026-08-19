package com.bablsoft.accessflow.compliance.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.compliance.api.ComplianceReportFormat;
import com.bablsoft.accessflow.compliance.api.ExportDecision;
import com.bablsoft.accessflow.compliance.api.ResultExportDeniedException;
import com.bablsoft.accessflow.compliance.api.ResultExportNotFoundException;
import com.bablsoft.accessflow.compliance.api.ResultExportService;
import com.bablsoft.accessflow.compliance.api.ResultExportUnavailableException;
import com.bablsoft.accessflow.compliance.api.ResultExportWatermark;
import com.bablsoft.accessflow.compliance.api.SignedExport;
import com.bablsoft.accessflow.compliance.internal.config.ComplianceProperties;
import com.bablsoft.accessflow.core.api.QueryResultPersistenceService;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.security.api.ExportSignatureService;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotService;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotView;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/**
 * Renders a query's persisted result snapshot to a signed, watermark-governed export (#626):
 * visibility check (QUERY_ADMIN or submitter, 404-hiding) → policy decision → row cap → render
 * with the watermark baked in → SHA-256 → RSA sign → fail-hard {@code RESULT_EXPORTED} audit →
 * sensitive-export event when classified. Cloned from {@link DefaultComplianceExportService};
 * like there, the audit write is integrity-critical and NOT swallowed — no audit row, no
 * download.
 */
@Service
@RequiredArgsConstructor
class DefaultResultExportService implements ResultExportService {

    private static final Logger log = LoggerFactory.getLogger(DefaultResultExportService.class);
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final TypeReference<List<List<Object>>> ROWS_TYPE = new TypeReference<>() {};

    private final DefaultResultExportGovernanceService governanceService;
    private final QuerySnapshotService querySnapshotService;
    private final QueryResultPersistenceService queryResultPersistenceService;
    private final ResultExportCsvWriter csvWriter;
    private final ResultExportPdfWriter pdfWriter;
    private final ExportSignatureService signatureService;
    private final AuditLogService auditLogService;
    private final ComplianceProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    public SignedExport export(UUID organizationId, UUID queryRequestId,
                               ComplianceReportFormat format, UUID actorUserId, String actorEmail,
                               boolean actorHasQueryAdmin, String ipAddress, String userAgent) {
        var snapshot = visibleSnapshot(organizationId, queryRequestId, actorUserId,
                actorHasQueryAdmin);
        var result = queryResultPersistenceService.find(queryRequestId)
                .orElseThrow(() -> new ResultExportNotFoundException(queryRequestId));
        var columnNames = governanceService.resultColumnNames(queryRequestId);
        var decision = governanceService.decide(snapshot, columnNames, actorUserId);
        if (!decision.allowed()) {
            throw new ResultExportDeniedException(decision.classificationsPresent());
        }

        var rows = parseRows(result.rowsJson(), queryRequestId);
        int formatCap = format == ComplianceReportFormat.PDF
                ? properties.resultExportMaxPdfRows()
                : properties.resultExportMaxRows();
        int effectiveCap = decision.rowCap() == null ? formatCap
                : Math.min(decision.rowCap(), formatCap);
        boolean capped = rows.size() > effectiveCap;
        if (capped) {
            rows = rows.subList(0, effectiveCap);
        }
        boolean truncated = capped || result.truncated();

        var generatedAt = Instant.now(clock);
        String watermarkHeader = null;
        String watermarkFooter = null;
        if (decision.watermark()) {
            watermarkHeader = ResultExportWatermark.header(actorEmail, generatedAt, queryRequestId);
            // Cap provenance names the cap that actually truncated the file (the policy cap or
            // the format cap, whichever bound) — and only when it truncated.
            watermarkFooter = ResultExportWatermark.footer(rows.size(),
                    capped ? effectiveCap : null);
        }

        byte[] content = format == ComplianceReportFormat.PDF
                ? pdfWriter.write(queryRequestId, columnNames, rows, watermarkHeader,
                        watermarkFooter, actorEmail, generatedAt, truncated)
                : csvWriter.write(columnNames, rows, watermarkHeader, watermarkFooter);
        String sha256 = sha256Hex(content);
        String signature = signatureService.sign(content);
        String algorithm = signatureService.algorithm();

        recordExportAudit(snapshot, format, actorUserId, ipAddress, userAgent, decision,
                rows.size(), truncated, sha256, signature, algorithm);
        governanceService.publishIfSensitive(organizationId, queryRequestId, actorUserId,
                actorEmail, format.name(), rows.size(), decision, "endpoint");

        return new SignedExport(content, filename(queryRequestId, format), contentType(format),
                sha256, signature, algorithm, truncated);
    }

    @Override
    public ExportDecision decisionFor(UUID organizationId, UUID queryRequestId, UUID actorUserId,
                                      boolean actorHasQueryAdmin) {
        var snapshot = visibleSnapshot(organizationId, queryRequestId, actorUserId,
                actorHasQueryAdmin);
        if (queryResultPersistenceService.find(queryRequestId).isEmpty()) {
            throw new ResultExportNotFoundException(queryRequestId);
        }
        var columnNames = governanceService.resultColumnNames(queryRequestId);
        return governanceService.decide(snapshot, columnNames, actorUserId);
    }

    /** QUERY_ADMIN or submitter; anything else is an indistinguishable 404 (information hiding). */
    private QuerySnapshotView visibleSnapshot(UUID organizationId, UUID queryRequestId,
                                              UUID actorUserId, boolean actorHasQueryAdmin) {
        var snapshot = querySnapshotService.find(queryRequestId, organizationId)
                .orElseThrow(() -> new ResultExportNotFoundException(queryRequestId));
        if (!actorHasQueryAdmin && !snapshot.submittedBy().equals(actorUserId)) {
            throw new ResultExportNotFoundException(queryRequestId);
        }
        if (snapshot.queryType() != QueryType.SELECT) {
            throw new ResultExportUnavailableException(queryRequestId);
        }
        return snapshot;
    }

    private List<List<Object>> parseRows(String rowsJson, UUID queryRequestId) {
        if (rowsJson == null || rowsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rowsJson, ROWS_TYPE);
        } catch (RuntimeException ex) {
            log.warn("Unparseable stored result rows for query {} — export unavailable",
                    queryRequestId, ex);
            throw new ResultExportNotFoundException(queryRequestId);
        }
    }

    private void recordExportAudit(QuerySnapshotView snapshot, ComplianceReportFormat format,
                                   UUID actorId, String ipAddress, String userAgent,
                                   ExportDecision decision, long rowCount, boolean truncated,
                                   String sha256, String signature, String algorithm) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("trigger", "endpoint");
        metadata.put("format", format.name());
        metadata.put("datasource_id", snapshot.datasourceId().toString());
        metadata.put("row_count", rowCount);
        metadata.put("truncated", truncated);
        metadata.put("watermarked", decision.watermark());
        metadata.put("policy_ids", decision.policyIds().stream().map(UUID::toString).toList());
        metadata.put("classifications_present",
                decision.classificationsPresent().stream().map(Enum::name).toList());
        metadata.put("content_sha256", sha256);
        metadata.put("signature", signature);
        metadata.put("signature_algorithm", algorithm);

        // Integrity-critical: a failed audit write must fail the export (do not swallow).
        auditLogService.record(new AuditEntry(
                AuditAction.RESULT_EXPORTED,
                AuditResourceType.QUERY_REQUEST,
                snapshot.queryRequestId(),
                snapshot.organizationId(),
                actorId,
                metadata,
                ipAddress,
                userAgent));
    }

    private String filename(UUID queryRequestId, ComplianceReportFormat format) {
        var ext = format == ComplianceReportFormat.PDF ? "pdf" : "csv";
        var idPrefix = queryRequestId.toString().substring(0, 8);
        return "query-results-" + idPrefix + "-" + FILE_STAMP.format(Instant.now(clock)) + "." + ext;
    }

    private static String contentType(ComplianceReportFormat format) {
        return format == ComplianceReportFormat.PDF ? "application/pdf" : "text/csv; charset=utf-8";
    }

    private static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
