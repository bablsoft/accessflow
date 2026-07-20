package com.bablsoft.accessflow.apigov.internal.schema;

import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiSchemaParseException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import com.bablsoft.accessflow.apigov.api.ParsedApiSchema;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses a Postman Collection v2.x export into a normalized operation catalog.
 *
 * <p>Postman carries <em>examples, not schemas</em>: {@code requestSchema} / {@code responseSchema}
 * are inferred from the saved example bodies, which is a real fidelity gap versus OpenAPI. Folders
 * are flattened into a slugified, deterministic {@code operationId} ({@code billing/invoices/
 * create-invoice}); collection-level {@code variable[]} entries are substituted into paths and every
 * remaining {@code {{var}}} becomes a {@code {var}} template.
 *
 * <p>Security: exports frequently contain live tokens and arbitrary pre-request/test JavaScript.
 * Only the auth <em>type</em> is read — never a credential value — and {@code event} blocks are
 * ignored entirely. The document persisted as the schema's raw content is the redacted one returned
 * as {@link ParsedApiSchema#sanitizedContent()}.
 */
@Component
public class PostmanCollectionParser implements ApiSchemaParser {

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    /** Mirrors DefaultApiSchemaService.MAX_FETCH_BYTES — bounds an attacker-influenced document. */
    private static final int MAX_DOCUMENT_CHARS = 5 * 1024 * 1024;
    private static final int MAX_OPERATIONS = 2000;
    /** Depth bound on folder recursion; well beyond any collection a human would author. */
    private static final int MAX_FOLDER_DEPTH = 32;

    private static final Pattern POSTMAN_VARIABLE = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");
    private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");

    private final ObjectMapper objectMapper;
    private final JsonShapeInferrer shapeInferrer;

    public PostmanCollectionParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.shapeInferrer = new JsonShapeInferrer(objectMapper);
    }

    @Override
    public ApiSchemaType supportedType() {
        return ApiSchemaType.POSTMAN_COLLECTION;
    }

    @Override
    public ParsedApiSchema parse(String content) {
        if (content == null || content.isBlank()) {
            throw new ApiSchemaParseException("Empty Postman collection document");
        }
        if (content.length() > MAX_DOCUMENT_CHARS) {
            throw new ApiSchemaParseException("Postman collection exceeds the maximum allowed size");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(content);
        } catch (RuntimeException ex) {
            throw new ApiSchemaParseException("Invalid Postman collection JSON: " + ex.getMessage());
        }
        if (!root.isObject()) {
            throw new ApiSchemaParseException("Postman collection must be a JSON object");
        }
        requireSupportedVersion(root);

        var variables = collectionVariables(root);
        var operations = new ArrayList<ApiOperation>();
        var usedIds = new HashMap<String, Integer>();
        walk(root.path("item"), "", variables, operations, usedIds, 0);
        if (operations.isEmpty()) {
            throw new ApiSchemaParseException("Postman collection defines no requests");
        }
        return new ParsedApiSchema(operations, detectAuthMethod(root.path("auth")), sanitize(root));
    }

    /**
     * Postman v1 has no {@code info.schema} and stores a flat {@code requests} array. v2.0 and v2.1
     * are identical for everything this parser reads, so both are accepted.
     */
    private static void requireSupportedVersion(JsonNode root) {
        var schema = root.path("info").path("schema").asString("");
        if (!schema.contains("/v2.")) {
            throw new ApiSchemaParseException(
                    "Unsupported Postman collection format — export the collection as Collection v2.1 "
                            + "(Postman: Collection ... > Export > Collection v2.1)");
        }
    }

    private static Map<String, String> collectionVariables(JsonNode root) {
        var variables = new LinkedHashMap<String, String>();
        for (var variable : root.path("variable")) {
            var key = variable.path("key").asString("");
            if (!key.isBlank()) {
                variables.put(key, variable.path("value").asString(""));
            }
        }
        return variables;
    }

    private void walk(JsonNode items, String prefix, Map<String, String> variables,
                      List<ApiOperation> out, Map<String, Integer> usedIds, int depth) {
        if (!items.isArray() || depth > MAX_FOLDER_DEPTH) {
            return;
        }
        for (var item : items) {
            if (out.size() >= MAX_OPERATIONS) {
                throw new ApiSchemaParseException(
                        "Postman collection defines more than " + MAX_OPERATIONS + " requests");
            }
            var segment = slugify(item.path("name").asString(""));
            var qualified = prefix.isEmpty() ? segment : prefix + "/" + segment;
            if (item.has("item")) {
                walk(item.path("item"), qualified, variables, out, usedIds, depth + 1);
            } else if (item.has("request")) {
                out.add(toOperation(item, uniqueId(qualified, usedIds), variables));
            }
        }
    }

    private ApiOperation toOperation(JsonNode item, String operationId, Map<String, String> variables) {
        var request = item.path("request");
        var verb = request.path("method").asString("GET").toUpperCase(Locale.ROOT);
        var summary = text(request.path("description"));
        return new ApiOperation(
                operationId,
                verb,
                pathOf(request.path("url"), variables),
                summary,
                !READ_METHODS.contains(verb),
                inferRequestSchema(request.path("body")),
                inferResponseSchema(item.path("response")),
                null,
                null);
    }

    /**
     * Builds a path template from the URL's {@code path} segments when present, else from
     * {@code raw} with scheme and host stripped — the connector's own {@code base_url} supplies the
     * origin. A {@code description} object (Postman wraps some strings) collapses to its content.
     */
    private static String pathOf(JsonNode url, Map<String, String> variables) {
        if (url.isTextual()) {
            return normalizePath(stripOrigin(url.asString("")), variables);
        }
        var segments = url.path("path");
        if (segments.isArray() && !segments.isEmpty()) {
            var joined = new StringBuilder();
            for (var segment : segments) {
                joined.append('/').append(segment.isTextual() ? segment.asString("") : text(segment));
            }
            return normalizePath(joined.toString(), variables);
        }
        return normalizePath(stripOrigin(url.path("raw").asString("")), variables);
    }

    private static String stripOrigin(String raw) {
        var withoutQuery = raw.split("[?#]", 2)[0];
        // Strip scheme://host, including the {{baseUrl}}/... form Postman exports use by default.
        var schemeless = withoutQuery.replaceFirst("^[A-Za-z][A-Za-z0-9+.-]*://", "");
        var slash = schemeless.indexOf('/');
        if (schemeless.startsWith("{{")) {
            return slash >= 0 ? schemeless.substring(slash) : "";
        }
        if (withoutQuery.length() != schemeless.length()) {
            return slash >= 0 ? schemeless.substring(slash) : "";
        }
        return withoutQuery;
    }

    /**
     * Substitutes collection-level variables, turns any remaining {@code {{var}}} into a {@code
     * {var}} template, and normalizes Postman's {@code :id} path params to the same {@code {id}}
     * form the rest of the catalog uses.
     */
    private static String normalizePath(String path, Map<String, String> variables) {
        var matcher = POSTMAN_VARIABLE.matcher(path);
        var resolved = matcher.replaceAll(match -> {
            var name = match.group(1);
            var value = variables.get(name);
            return java.util.regex.Matcher.quoteReplacement(
                    value != null && !value.isBlank() ? value : "{" + name + "}");
        });
        // A resolved variable may itself have carried an origin (baseUrl=https://api.example.com).
        resolved = stripOrigin(resolved);
        var segments = resolved.split("/", -1);
        var rebuilt = new StringBuilder();
        for (var segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            rebuilt.append('/').append(segment.startsWith(":") ? "{" + segment.substring(1) + "}" : segment);
        }
        return rebuilt.isEmpty() ? "/" : rebuilt.toString();
    }

    private String inferRequestSchema(JsonNode body) {
        return switch (body.path("mode").asString("")) {
            case "raw" -> shapeInferrer.inferFromJson(body.path("raw").asString(""));
            case "urlencoded" -> shapeInferrer.inferFromFields(keyValueFields(body.path("urlencoded")));
            case "formdata" -> shapeInferrer.inferFromFields(keyValueFields(body.path("formdata")));
            default -> null;
        };
    }

    /** Uses the first saved example response; collections often save none at all. */
    private String inferResponseSchema(JsonNode responses) {
        if (!responses.isArray()) {
            return null;
        }
        for (var response : responses) {
            var shape = shapeInferrer.inferFromJson(response.path("body").asString(""));
            if (shape != null) {
                return shape;
            }
        }
        return null;
    }

    private static Map<String, String> keyValueFields(JsonNode entries) {
        var fields = new LinkedHashMap<String, String>();
        for (var entry : entries) {
            if (entry.path("disabled").asBoolean(false)) {
                continue;
            }
            var key = entry.path("key").asString("");
            if (!key.isBlank()) {
                fields.put(key, entry.path("value").asString(""));
            }
        }
        return fields;
    }

    /** Reads the declared auth <em>type</em> only. Credential values are never touched. */
    private static ApiAuthMethod detectAuthMethod(JsonNode auth) {
        return switch (auth.path("type").asString("").toLowerCase(Locale.ROOT)) {
            case "apikey" -> ApiAuthMethod.API_KEY;
            case "bearer", "jwt" -> ApiAuthMethod.BEARER_TOKEN;
            case "basic" -> ApiAuthMethod.BASIC;
            case "oauth2" -> ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS;
            case "noauth" -> ApiAuthMethod.NONE;
            default -> null;
        };
    }

    /**
     * Returns the collection with every credential value and every {@code event} script removed, so
     * that what is persisted as raw content can never leak a token exported from Postman.
     */
    private String sanitize(JsonNode root) {
        var copy = root.deepCopy();
        redact(copy, 0);
        return objectMapper.writeValueAsString(copy);
    }

    private static void redact(JsonNode node, int depth) {
        if (depth > MAX_FOLDER_DEPTH) {
            return;
        }
        if (node instanceof ObjectNode object) {
            // Pre-request / test JavaScript is never stored, evaluated, or shown to the analyzer.
            object.remove("event");
            if (object.get("auth") instanceof ObjectNode auth) {
                // Keep only the declared type; drop every scheme's credential array wholesale.
                var type = auth.path("type").asString("");
                auth.removeAll();
                if (!type.isBlank()) {
                    auth.put("type", type);
                }
            }
        }
        for (var child : node) {
            redact(child, depth + 1);
        }
    }

    private static String uniqueId(String candidate, Map<String, Integer> usedIds) {
        var base = candidate.isBlank() ? "request" : candidate;
        var seen = usedIds.merge(base, 1, Integer::sum);
        return seen == 1 ? base : base + "-" + seen;
    }

    private static String slugify(String name) {
        var slug = NON_SLUG.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("-");
        slug = slug.replaceAll("^-+|-+$", "");
        return slug.isBlank() ? "request" : slug;
    }

    private static String text(JsonNode node) {
        if (node.isTextual()) {
            var value = node.asString("");
            return value.isBlank() ? null : value;
        }
        if (node.isObject()) {
            var content = node.path("content").asString("");
            return content.isBlank() ? null : content;
        }
        return null;
    }
}
