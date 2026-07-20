package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiBodyType;
import com.bablsoft.accessflow.apigov.api.ApiFormField;
import com.bablsoft.accessflow.apigov.api.ApiVariableTargetType;
import com.bablsoft.accessflow.apigov.api.ResolvedApiVariables;
import com.bablsoft.accessflow.apigov.internal.client.ApiCallRequest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Applies resolved connector variables to a composed outbound call (AF-613): substitutes
 * {@code {{name}}} placeholders, then applies any {@code header:} / {@code query:} auto-injections.
 *
 * <p>What is deliberately <em>not</em> substituted matters as much as what is:
 * <ul>
 *   <li><b>Header names and query keys</b> — a variable-named header could not be meaningfully
 *       reviewed, since the reviewer would not know what the request will actually send.</li>
 *   <li><b>{@code baseUrl}</b> — a variable-controlled host turns a governed call into an SSRF
 *       pivot. The target of a connector is admin config and stays fixed.</li>
 *   <li><b>Binary bodies and {@code FILE} form parts</b> — those are base64; substituting inside
 *       them would corrupt the payload rather than template it.</li>
 * </ul>
 */
final class ApiRequestVariableSubstitution {

    private ApiRequestVariableSubstitution() {
    }

    /** The canonical {@code {{request.query}}} serialization: sorted, percent-encoded, {@code &}-joined. */
    static String canonicalQuery(Map<String, String> queryParams) {
        if (queryParams == null || queryParams.isEmpty()) {
            return "";
        }
        // Sorted rather than wire order, so a signature over it reproduces regardless of how the map
        // was built. The executor's own URL assembly may emit a different order; that is intentional
        // and documented — the canonical form exists for signing, not for transport.
        var sorted = new TreeMap<>(queryParams);
        var out = new StringBuilder();
        sorted.forEach((key, value) -> {
            if (!out.isEmpty()) {
                out.append('&');
            }
            out.append(URLEncoder.encode(key, StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8));
        });
        return out.toString();
    }

    /**
     * The body as {@code {{request.body}}} sees it. {@code FORM_DATA} yields the empty string:
     * multipart boundaries are randomly generated per send, so no signature over the assembled
     * multipart payload could ever be reproduced.
     */
    static String bodyForContext(ApiCallRequest request) {
        var bodyType = request.bodyType() == null ? ApiBodyType.RAW : request.bodyType();
        return switch (bodyType) {
            case RAW, BINARY -> request.body() == null ? "" : request.body();
            case FORM_URLENCODED -> canonicalQuery(formFieldsAsMap(request));
            case NONE, FORM_DATA -> "";
        };
    }

    private static Map<String, String> formFieldsAsMap(ApiCallRequest request) {
        var map = new LinkedHashMap<String, String>();
        if (request.formFields() != null) {
            request.formFields().forEach(f -> map.put(f.key(), f.value()));
        }
        return map;
    }

    /** Returns {@code request} with placeholders substituted and injections applied. */
    static ApiCallRequest apply(ApiCallRequest request, ResolvedApiVariables resolved) {
        if (resolved == null || resolved.isEmpty()) {
            return request;
        }

        var headers = new LinkedHashMap<String, String>();
        request.headers().forEach((name, value) -> headers.put(name, substitute(value, resolved)));

        var queryParams = new LinkedHashMap<String, String>();
        request.queryParams().forEach((key, value) -> queryParams.put(key, substitute(value, resolved)));

        for (var injection : resolved.injections()) {
            if (injection.type() == ApiVariableTargetType.HEADER) {
                headers.put(injection.key(), injection.value());
            } else {
                queryParams.put(injection.key(), injection.value());
            }
        }

        var bodyType = request.bodyType() == null ? ApiBodyType.RAW : request.bodyType();
        // BINARY bodies are base64 — substituting inside one would corrupt it, not template it.
        var body = bodyType == ApiBodyType.RAW ? substitute(request.body(), resolved) : request.body();

        var formFields = new ArrayList<ApiFormField>();
        if (request.formFields() != null) {
            for (var field : request.formFields()) {
                formFields.add(field.type() == ApiFormField.ApiFormFieldType.TEXT
                        ? new ApiFormField(field.key(), field.type(), substitute(field.value(), resolved),
                                field.filename(), field.contentType())
                        : field);
            }
        }

        return new ApiCallRequest(request.protocol(), request.baseUrl(), request.verb(),
                substitute(request.path(), resolved), headers, queryParams, request.bodyType(), body,
                request.contentType(), formFields, request.binaryFilename(), request.timeoutMs(),
                request.maxResponseBytes(), request.operationId());
    }

    /**
     * Single-pass substitution against the resolved values. {@code {{request.*}}} has no meaning
     * here — there is no expression being evaluated — so such a reference resolves to nothing and is
     * left literal.
     */
    private static String substitute(String template, ResolvedApiVariables resolved) {
        return ApiVariableTemplate.render(template,
                ref -> ref.isVariable() ? resolved.values().get(ref.key()) : null,
                ApiVariableTemplate.SUBSTITUTION_STRICT_SCOPES);
    }
}
