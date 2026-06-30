package com.bablsoft.accessflow.apigov.api;

/**
 * How an API-connector masking policy / classification tag targets a field in a (non-tabular) API
 * response (AF-518):
 * <ul>
 *   <li>{@link #SCHEMA_FIELD} — a field of a parsed schema operation ({@code operationId} + field);
 *       resolved to a concrete response path through the connector's operation catalog.</li>
 *   <li>{@link #JSON_PATH} — a dot-path into the JSON response body (e.g. {@code user.email}),
 *       descending through arrays.</li>
 *   <li>{@link #XML_PATH} — an XPath into an XML/SOAP response body.</li>
 *   <li>{@link #REGEX} — a regular expression matched over a JSON or text body.</li>
 * </ul>
 */
public enum ApiMaskingMatcherType {
    SCHEMA_FIELD,
    JSON_PATH,
    XML_PATH,
    REGEX
}
