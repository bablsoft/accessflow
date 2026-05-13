package com.bablsoft.accessflow.audit.api;

/**
 * IP address and user-agent of the live HTTP request, attached to synchronous audit rows.
 * Controllers receive this as a method parameter resolved by
 * {@code RequestAuditContextArgumentResolver}; honors {@code X-Forwarded-For} when present so
 * traffic terminated at the ingress is attributed to the real client.
 */
public record RequestAuditContext(String ipAddress, String userAgent) {
}
