package com.bablsoft.accessflow.apigov.api;

import java.util.List;

/**
 * Result of parsing an uploaded API schema document.
 *
 * <p>{@code detectedAuthMethod} is the authentication scheme the document itself declares, when the
 * format carries one (Postman collections do; OpenAPI/WSDL/SDL/proto currently do not) — a hint for
 * the admin only. The credential values are never read.
 *
 * <p>{@code sanitizedContent} lets a parser replace what gets persisted as the schema's raw content.
 * {@code null} means "store the uploaded document unchanged"; a non-null value is stored instead.
 * Postman exports routinely carry live tokens and arbitrary pre-request/test JavaScript, neither of
 * which may be retained.
 */
public record ParsedApiSchema(
        List<ApiOperation> operations,
        ApiAuthMethod detectedAuthMethod,
        String sanitizedContent) {

    /** For parsers whose format declares no auth and needs no sanitization. */
    public ParsedApiSchema(List<ApiOperation> operations) {
        this(operations, null, null);
    }
}
