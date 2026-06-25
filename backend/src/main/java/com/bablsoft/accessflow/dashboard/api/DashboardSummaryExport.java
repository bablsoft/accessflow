package com.bablsoft.accessflow.dashboard.api;

/**
 * A rendered, digitally-signed dashboard weekly-summary export (AF-498). Mirrors the shape of the
 * compliance signed export but is dashboard-owned (no cross-module DTO sharing). {@code signatureBase64}
 * and {@code contentSha256Hex} are stamped into response headers; the SHA-256 is also chained into the
 * audit log.
 */
public record DashboardSummaryExport(
        byte[] content,
        String filename,
        String contentType,
        String contentSha256Hex,
        String signatureBase64,
        String signatureAlgorithm) {
}
