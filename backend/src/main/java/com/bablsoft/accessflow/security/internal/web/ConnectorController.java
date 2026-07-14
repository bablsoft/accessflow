package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.DriverCatalogService;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.internal.web.model.ConnectorListResponse;
import com.bablsoft.accessflow.security.internal.web.model.ConnectorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Connector marketplace: lists the declarative connector catalog and installs a connector's JDBC
 * driver on demand (download + SHA-256 verify + cache). Distinct from
 * {@code /datasources/drivers} (admin-uploaded JARs); connectors are the curated, pre-defined
 * catalog. Admin-only.
 */
@RestController
@RequestMapping("/api/v1/datasources/connectors")
@Tag(name = "Connectors", description = "Database connector catalog and on-demand driver install")
@RequiredArgsConstructor
@Slf4j
class ConnectorController {

    private final DriverCatalogService driverCatalogService;
    private final AuditLogService auditLogService;

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_DATASOURCE_MANAGE')")
    @Operation(summary = "List database connectors with install status")
    @ApiResponse(responseCode = "200", description = "Connector catalog")
    ConnectorListResponse listConnectors() {
        return ConnectorListResponse.from(driverCatalogService.listConnectors());
    }

    @PostMapping("/{id}/install")
    @PreAuthorize("hasAuthority('PERM_DATASOURCE_MANAGE')")
    @Operation(summary = "Install a connector by downloading and caching its JDBC driver")
    @ApiResponse(responseCode = "200", description = "Connector installed; returns updated status")
    @ApiResponse(responseCode = "404", description = "Unknown connector id")
    @ApiResponse(responseCode = "422", description = "Driver download or SHA-256 verification failed")
    ConnectorResponse install(@PathVariable String id, Authentication authentication,
                              RequestAuditContext auditContext) {
        var caller = currentClaims(authentication);
        var info = driverCatalogService.install(id);
        var metadata = new HashMap<String, Object>();
        metadata.put("connector_id", id);
        metadata.put("db_type", info.code().name());
        metadata.put("driver_status", info.driverStatus().name());
        recordAudit(caller, auditContext, metadata);
        return ConnectorResponse.from(info);
    }

    private JwtClaims currentClaims(Authentication authentication) {
        return (JwtClaims) authentication.getPrincipal();
    }

    private void recordAudit(JwtClaims caller, RequestAuditContext auditContext,
                             Map<String, Object> metadata) {
        try {
            auditLogService.record(new AuditEntry(
                    AuditAction.CONNECTOR_INSTALLED,
                    AuditResourceType.CONNECTOR,
                    null,
                    caller.organizationId(),
                    caller.userId(),
                    new HashMap<>(metadata),
                    auditContext.ipAddress(),
                    auditContext.userAgent()));
        } catch (RuntimeException ex) {
            log.error("Audit write failed for {} on {}", AuditAction.CONNECTOR_INSTALLED,
                    AuditResourceType.CONNECTOR.dbValue(), ex);
        }
    }
}
