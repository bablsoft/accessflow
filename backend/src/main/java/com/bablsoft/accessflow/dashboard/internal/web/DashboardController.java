package com.bablsoft.accessflow.dashboard.internal.web;

import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.dashboard.api.DashboardService;
import com.bablsoft.accessflow.dashboard.api.DashboardSummaryExportService;
import com.bablsoft.accessflow.dashboard.api.DashboardSummaryFormat;
import com.bablsoft.accessflow.dashboard.api.DashboardSuggestionService;
import com.bablsoft.accessflow.dashboard.api.DigestSubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The personalized dashboard surface (AF-498): a single self-scoped aggregate, the user's query
 * trends, their AI optimization backlog, an opt-in weekly-digest toggle, and an on-demand signed
 * weekly-summary export. Every endpoint is bound to the authenticated caller's own data — available
 * to any authenticated user, no admin role required.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Dashboard", description = "Personalized self-scoped dashboard and insights")
@RequiredArgsConstructor
class DashboardController {

    private final DashboardService dashboardService;
    private final DashboardSuggestionService suggestionService;
    private final DashboardSummaryExportService exportService;
    private final DigestSubscriptionService digestSubscriptionService;

    @GetMapping("/summary")
    @Operation(summary = "Self-scoped dashboard summary: counts + short recent lists for the caller")
    @ApiResponse(responseCode = "200", description = "The dashboard summary")
    DashboardSummaryResponse summary(
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId,
            @AuthenticationPrincipal(expression = "userId") UUID userId,
            @AuthenticationPrincipal(expression = "role") UserRoleType role) {
        return DashboardSummaryResponse.from(dashboardService.summary(organizationId, userId, role));
    }

    @GetMapping("/my-query-trends")
    @Operation(summary = "Day-bucketed status/risk trend series for the caller's own queries")
    @ApiResponse(responseCode = "200", description = "The trend series")
    MyQueryTrendsResponse trends(
            @Parameter(description = "Inclusive lower bound; defaults to now-30d")
            @RequestParam(required = false) Instant from,
            @Parameter(description = "Exclusive upper bound; defaults to now")
            @RequestParam(required = false) Instant to,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId,
            @AuthenticationPrincipal(expression = "userId") UUID userId) {
        return MyQueryTrendsResponse.from(dashboardService.trends(organizationId, userId, from, to));
    }

    @GetMapping("/my-api-request-trends")
    @Operation(summary = "Day-bucketed status/risk trend series for the caller's own governed API requests")
    @ApiResponse(responseCode = "200", description = "The trend series")
    MyApiRequestTrendsResponse apiRequestTrends(
            @Parameter(description = "Inclusive lower bound; defaults to now-30d")
            @RequestParam(required = false) Instant from,
            @Parameter(description = "Exclusive upper bound; defaults to now")
            @RequestParam(required = false) Instant to,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId,
            @AuthenticationPrincipal(expression = "userId") UUID userId) {
        return MyApiRequestTrendsResponse.from(
                dashboardService.apiRequestTrends(organizationId, userId, from, to));
    }

    @GetMapping("/suggestions")
    @Operation(summary = "The caller's open AI optimization-suggestion backlog")
    @ApiResponse(responseCode = "200", description = "The backlog")
    DashboardSuggestionsResponse suggestions(
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId,
            @AuthenticationPrincipal(expression = "userId") UUID userId) {
        return DashboardSuggestionsResponse.from(suggestionService.listOpen(organizationId, userId));
    }

    @PostMapping("/suggestions/{id}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Dismiss an AI optimization suggestion from the caller's backlog")
    @ApiResponse(responseCode = "204", description = "Dismissed")
    @ApiResponse(responseCode = "404", description = "Unknown / not owned by the caller")
    void dismissSuggestion(
            @PathVariable String id,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId,
            @AuthenticationPrincipal(expression = "userId") UUID userId) {
        suggestionService.dismiss(organizationId, userId, id);
    }

    @GetMapping("/digest-subscription")
    @Operation(summary = "The caller's weekly-digest opt-in state")
    @ApiResponse(responseCode = "200", description = "The subscription state")
    DigestSubscriptionResponse getDigestSubscription(
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId,
            @AuthenticationPrincipal(expression = "userId") UUID userId) {
        return DigestSubscriptionResponse.from(digestSubscriptionService.get(organizationId, userId));
    }

    @PutMapping("/digest-subscription")
    @Operation(summary = "Enable or disable the weekly-digest email for the caller")
    @ApiResponse(responseCode = "200", description = "The updated subscription state")
    @ApiResponse(responseCode = "400", description = "Missing enabled flag")
    DigestSubscriptionResponse setDigestSubscription(
            @Valid @RequestBody UpdateDigestSubscriptionRequest request,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId,
            @AuthenticationPrincipal(expression = "userId") UUID userId) {
        return DigestSubscriptionResponse.from(
                digestSubscriptionService.set(organizationId, userId, request.enabled()));
    }

    @GetMapping("/summary/export")
    @Operation(summary = "Render the caller's weekly summary as a digitally-signed PDF or CSV")
    @ApiResponse(responseCode = "200", description = "Signed export stream")
    void export(
            @Parameter(description = "Any date in the target ISO week; defaults to the current week")
            @RequestParam(required = false) LocalDate week,
            @Parameter(description = "Export format") @RequestParam DashboardSummaryFormat format,
            @AuthenticationPrincipal(expression = "organizationId") UUID organizationId,
            @AuthenticationPrincipal(expression = "userId") UUID userId,
            RequestAuditContext auditContext,
            HttpServletResponse response) throws IOException {
        var export = exportService.export(organizationId, userId, week, format, userId,
                auditContext == null ? null : auditContext.ipAddress(),
                auditContext == null ? null : auditContext.userAgent());

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(export.contentType());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + export.filename() + "\"");
        response.setHeader("X-AccessFlow-Signature", export.signatureBase64());
        response.setHeader("X-AccessFlow-Signature-Algorithm", export.signatureAlgorithm());
        response.setHeader("X-AccessFlow-Content-SHA256", export.contentSha256Hex());
        response.getOutputStream().write(export.content());
        response.getOutputStream().flush();
    }
}
