package com.bablsoft.accessflow.compliance.internal;

import com.bablsoft.accessflow.compliance.api.ComplianceReport;
import com.bablsoft.accessflow.compliance.api.ComplianceReportRequest;
import com.bablsoft.accessflow.compliance.api.ComplianceReportService;
import com.bablsoft.accessflow.compliance.api.InvalidReportPeriodException;
import com.bablsoft.accessflow.compliance.api.RegulatoryAuditTrailRow;
import com.bablsoft.accessflow.compliance.internal.config.ComplianceProperties;
import com.bablsoft.accessflow.core.api.DataClassificationAdminService;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceView;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.UserAdminService;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotService;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class DefaultComplianceReportService implements ComplianceReportService {

    private static final Set<QueryType> REGULATORY_TYPES = Set.of(QueryType.DDL, QueryType.DELETE);
    private static final int DATASOURCE_NAME_PAGE_SIZE = 1000;
    private static final String INVALID_PERIOD = "error.invalid_report_period";

    private final QuerySnapshotService snapshotService;
    private final DataClassificationAdminService dataClassificationAdminService;
    private final DatasourceAdminService datasourceAdminService;
    private final UserAdminService userAdminService;
    private final ReviewDecisionsParser reviewDecisionsParser;
    private final ComplianceProperties properties;
    private final Clock clock;

    @Override
    public ComplianceReport generate(UUID organizationId, ComplianceReportRequest request) {
        validatePeriod(request);
        int cap = properties.maxRows();
        return switch (request.type()) {
            case CLASSIFIED_ACCESS -> classifiedAccess(organizationId, request, cap);
            case REGULATORY_AUDIT_TRAIL -> regulatoryTrail(organizationId, request, cap);
        };
    }

    private ComplianceReport classifiedAccess(UUID organizationId, ComplianceReportRequest request,
                                              int cap) {
        var raw = snapshotService.findForPeriod(organizationId, request.from(), request.to(),
                request.datasourceId(), null, cap + 1);
        boolean truncated = raw.size() > cap;
        var snapshots = truncated ? raw.subList(0, cap) : raw;

        var classifications = dataClassificationAdminService.listForOrganization(organizationId);
        var emails = submitterEmails(organizationId, snapshots);
        var datasourceNames = datasourceNames(organizationId);
        var rows = ClassificationJoiner.join(snapshots, classifications, emails, datasourceNames);

        return new ComplianceReport(request.type(), organizationId, request.from(), request.to(),
                Instant.now(clock), request.datasourceId(), rows, List.of(), truncated);
    }

    private ComplianceReport regulatoryTrail(UUID organizationId, ComplianceReportRequest request,
                                             int cap) {
        var raw = snapshotService.findForPeriod(organizationId, request.from(), request.to(),
                request.datasourceId(), REGULATORY_TYPES, cap + 1);
        boolean truncated = raw.size() > cap;
        var snapshots = truncated ? raw.subList(0, cap) : raw;

        var emails = submitterEmails(organizationId, snapshots);
        var datasourceNames = datasourceNames(organizationId);

        var rows = new ArrayList<RegulatoryAuditTrailRow>(snapshots.size());
        for (var snapshot : snapshots) {
            rows.add(new RegulatoryAuditTrailRow(
                    snapshot.queryRequestId(),
                    snapshot.datasourceId(),
                    datasourceNames.get(snapshot.datasourceId()),
                    snapshot.submittedBy(),
                    emails.get(snapshot.submittedBy()),
                    snapshot.queryType(),
                    snapshot.sqlText(),
                    reviewDecisionsParser.approvers(snapshot.reviewDecisionsJson()),
                    snapshot.executedAt()));
        }

        return new ComplianceReport(request.type(), organizationId, request.from(), request.to(),
                Instant.now(clock), request.datasourceId(), List.of(), rows, truncated);
    }

    private void validatePeriod(ComplianceReportRequest request) {
        if (request.from() == null || request.to() == null
                || request.from().isAfter(request.to())) {
            throw new InvalidReportPeriodException(INVALID_PERIOD);
        }
        if (Duration.between(request.from(), request.to()).compareTo(properties.maxReportPeriod()) > 0) {
            throw new InvalidReportPeriodException(INVALID_PERIOD);
        }
    }

    private Map<UUID, String> submitterEmails(UUID organizationId, List<QuerySnapshotView> snapshots) {
        Set<UUID> ids = new HashSet<>();
        for (var snapshot : snapshots) {
            if (snapshot.submittedBy() != null) {
                ids.add(snapshot.submittedBy());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> emails = new LinkedHashMap<>();
        userAdminService.findByIds(organizationId, ids)
                .forEach((id, user) -> emails.put(id, user.email()));
        return emails;
    }

    private Map<UUID, String> datasourceNames(UUID organizationId) {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (DatasourceView ds : datasourceAdminService
                .listForAdmin(organizationId, PageRequest.of(0, DATASOURCE_NAME_PAGE_SIZE)).content()) {
            names.put(ds.id(), ds.name());
        }
        return names;
    }
}
