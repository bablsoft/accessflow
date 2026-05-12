package com.bablsoft.accessflow.audit.api;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Extracts the {@code ip_address} / {@code user_agent} pair to attach to a synchronous audit row
 * from the live HTTP request. Honors {@code X-Forwarded-For} when present so traffic terminated
 * at the ingress is attributed to the real client.
 */
public record RequestAuditContext(String ipAddress, String userAgent) {

    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String USER_AGENT = "User-Agent";

    public static RequestAuditContext from(HttpServletRequest request) {
        if (request == null) {
            return new RequestAuditContext(null, null);
        }
        return new RequestAuditContext(extractIp(request), request.getHeader(USER_AGENT));
    }

    private static String extractIp(HttpServletRequest request) {
        var forwarded = request.getHeader(FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            var first = forwarded.split(",", 2)[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }
}
