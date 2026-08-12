package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.GrantUsageExportService;
import com.bablsoft.accessflow.access.api.GrantUsageReportQuery;
import com.bablsoft.accessflow.access.api.GrantUsageService;
import com.bablsoft.accessflow.access.api.GrantUsageView;
import com.bablsoft.accessflow.access.internal.config.AccessProperties;
import com.bablsoft.accessflow.core.api.PageRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Renders the over-provisioned access report as CSV (#625). Assembly, the row cap and the filename
 * stamp all live here rather than in the controller, which stays a binding-and-headers shim.
 */
@Service
@RequiredArgsConstructor
@Slf4j
class DefaultGrantUsageExportService implements GrantUsageExportService {

    private static final String[] HEADER = {
            "summary_id", "resource_kind", "resource_name", "resource_id", "user_email",
            "user_display_name", "granted_at", "expires_at", "granted_target_count",
            "used_target_count", "unused_target_count", "used_targets", "usage_count",
            "first_used_at", "last_used_at", "observed_since", "days_since_last_use",
            "usage_per_week", "recommendation"
    };

    private static final DateTimeFormatter FILENAME_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private final GrantUsageService grantUsageService;
    private final AccessProperties properties;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public UsageExport export(UUID organizationId, GrantUsageReportQuery query) {
        int cap = properties.usage().maxReportRows();
        var now = clock.instant();

        // Fetch cap + 1 so a full page is distinguishable from "exactly cap rows exist" — the same
        // truncation probe the compliance exporter uses.
        var page = grantUsageService.report(organizationId,
                query == null ? GrantUsageReportQuery.empty() : query,
                PageRequest.of(0, cap + 1));
        var rows = page.content();
        boolean truncated = rows.size() > cap;
        if (truncated) {
            rows = rows.subList(0, cap);
            log.warn("Over-provisioned access export for org {} truncated at {} rows",
                    organizationId, cap);
        }

        var sb = new StringBuilder();
        GrantUsageCsvWriter.appendRow(sb, HEADER);
        for (GrantUsageView row : rows) {
            GrantUsageCsvWriter.appendRow(sb,
                    row.id().toString(),
                    row.resourceKind().name(),
                    row.resourceName(),
                    row.resourceId().toString(),
                    row.userEmail(),
                    row.userDisplayName(),
                    text(row.grantedAt()),
                    text(row.expiresAt()),
                    text(row.grantedTargetCount()),
                    Integer.toString(row.usedTargetCount()),
                    text(row.unusedTargetCount()),
                    String.join(" ", row.usedTargets()),
                    Long.toString(row.usageCount()),
                    text(row.firstUsedAt()),
                    text(row.lastUsedAt()),
                    text(row.observedSince()),
                    text(row.daysSinceLastUse(now)),
                    text(row.usagePerWeek(now)),
                    row.recommendation().name());
        }

        var filename = "over-provisioned-access-" + FILENAME_STAMP.format(now.atZone(clock.getZone()))
                + ".csv";
        return new UsageExport(sb.toString().getBytes(StandardCharsets.UTF_8), filename, rows.size(),
                truncated);
    }

    /** Empty rather than "null" — a nullable figure means "not applicable", not the string null. */
    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
