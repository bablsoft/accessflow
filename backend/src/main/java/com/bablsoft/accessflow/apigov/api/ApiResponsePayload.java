package com.bablsoft.accessflow.apigov.api;

/**
 * The downloadable response body of an executed API request: the stored (masked, size-capped)
 * snapshot bytes, the upstream content type (falling back to {@code application/octet-stream}), and a
 * suggested attachment filename with an extension inferred from the content type.
 */
public record ApiResponsePayload(byte[] content, String contentType, String filename) {
}
