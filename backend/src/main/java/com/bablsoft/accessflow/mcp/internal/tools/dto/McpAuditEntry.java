package com.bablsoft.accessflow.mcp.internal.tools.dto;

import com.bablsoft.accessflow.audit.api.AuditLogView;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One audit row returned by the {@code get_audit_log} MCP tool. The organization and actor ids are
 * omitted — the tool only ever returns the caller's own entries within their own organization, so
 * both are constant. {@code resourceType} is the snake_case db value (e.g. {@code query_request}).
 */
public record McpAuditEntry(
        UUID id,
        String action,
        String resourceType,
        UUID resourceId,
        Map<String, Object> metadata,
        String ipAddress,
        String userAgent,
        Instant createdAt) {

    public static McpAuditEntry from(AuditLogView view) {
        return new McpAuditEntry(
                view.id(),
                view.action().name(),
                view.resourceType() == null ? null : view.resourceType().dbValue(),
                view.resourceId(),
                view.metadata(),
                view.ipAddress(),
                view.userAgent(),
                view.createdAt());
    }
}
