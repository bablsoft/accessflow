package com.bablsoft.accessflow.apigov.internal.schema;

import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiSchemaParseException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostmanCollectionParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PostmanCollectionParser parser = new PostmanCollectionParser(objectMapper);

    private static final String V21_HEADER = """
            "info": {
              "name": "Billing",
              "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
            }""";

    private static final String COLLECTION = """
            {
              %s,
              "variable": [{ "key": "baseUrl", "value": "https://api.example.com" }],
              "auth": { "type": "bearer", "bearer": [{ "key": "token", "value": "SUPER-SECRET" }] },
              "item": [
                {
                  "name": "Invoices",
                  "item": [
                    {
                      "name": "Create Invoice",
                      "event": [{ "listen": "prerequest", "script": { "exec": ["pm.environment.set('x', 1)"] } }],
                      "request": {
                        "method": "POST",
                        "description": "Creates an invoice",
                        "url": { "raw": "{{baseUrl}}/v1/invoices", "path": ["v1", "invoices"] },
                        "body": { "mode": "raw", "raw": "{\\"amount\\": 10, \\"paid\\": false}" }
                      },
                      "response": [{ "body": "{\\"id\\": \\"inv_1\\"}" }]
                    },
                    {
                      "name": "Get Invoice",
                      "request": {
                        "method": "GET",
                        "url": { "raw": "{{baseUrl}}/v1/invoices/:id", "path": ["v1", "invoices", ":id"] }
                      }
                    }
                  ]
                },
                {
                  "name": "Ping",
                  "request": { "method": "GET", "url": "{{baseUrl}}/{{stage}}/ping" }
                }
              ]
            }""".formatted(V21_HEADER);

    private static String collectionWith(String body) {
        return """
                { %s, "item": [{ "name": "R", "request": { "method": "POST",
                  "url": { "path": ["x"] }, "body": %s } }] }""".formatted(V21_HEADER, body);
    }

    private ApiOperation operation(List<ApiOperation> ops, String id) {
        return ops.stream().filter(o -> o.operationId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void supportedTypeIsPostmanCollection() {
        assertThat(parser.supportedType()).isEqualTo(ApiSchemaType.POSTMAN_COLLECTION);
    }

    @Test
    void flattensFoldersIntoSlugifiedOperationIds() {
        var ops = parser.parse(COLLECTION).operations();

        assertThat(ops).extracting(ApiOperation::operationId)
                .containsExactly("invoices/create-invoice", "invoices/get-invoice", "ping");
    }

    @Test
    void mapsVerbSummaryAndReadWriteClassification() {
        var ops = parser.parse(COLLECTION).operations();

        var create = operation(ops, "invoices/create-invoice");
        assertThat(create.verb()).isEqualTo("POST");
        assertThat(create.summary()).isEqualTo("Creates an invoice");
        assertThat(create.write()).isTrue();
        assertThat(operation(ops, "invoices/get-invoice").write()).isFalse();
    }

    @Test
    void resolvesCollectionVariablesAndStripsTheOrigin() {
        var ops = parser.parse(COLLECTION).operations();

        assertThat(operation(ops, "invoices/create-invoice").path()).isEqualTo("/v1/invoices");
    }

    @Test
    void normalizesUnresolvedVariablesAndPathParamsToTemplates() {
        var ops = parser.parse(COLLECTION).operations();

        // :id is Postman's path-param form; {{stage}} has no collection-level value.
        assertThat(operation(ops, "invoices/get-invoice").path()).isEqualTo("/v1/invoices/{id}");
        assertThat(operation(ops, "ping").path()).isEqualTo("/{stage}/ping");
    }

    @Test
    void infersRequestAndResponseSchemasFromExamples() {
        var ops = parser.parse(COLLECTION).operations();

        var create = operation(ops, "invoices/create-invoice");
        assertThat(create.requestSchema())
                .contains("\"amount\"").contains("\"integer\"")
                .contains("\"paid\"").contains("\"boolean\"");
        assertThat(create.responseSchema()).contains("\"id\"").contains("\"string\"");
    }

    @Test
    void leavesResponseSchemaNullWhenNoExampleIsSaved() {
        var ops = parser.parse(COLLECTION).operations();

        assertThat(operation(ops, "invoices/get-invoice").responseSchema()).isNull();
    }

    @Test
    void infersUrlencodedAndFormdataBodiesAsStringProperties() {
        var urlencoded = parser.parse(collectionWith(
                """
                { "mode": "urlencoded", "urlencoded": [
                  { "key": "grant_type", "value": "client_credentials" },
                  { "key": "skipped", "value": "x", "disabled": true }] }"""))
                .operations().getFirst();
        var formdata = parser.parse(collectionWith(
                """
                { "mode": "formdata", "formdata": [{ "key": "file", "value": "@a.png" }] }"""))
                .operations().getFirst();

        assertThat(urlencoded.requestSchema()).contains("\"grant_type\"").doesNotContain("skipped");
        assertThat(formdata.requestSchema()).contains("\"file\"").contains("\"string\"");
    }

    @Test
    void leavesRequestSchemaNullForUnsupportedOrNonJsonBodies() {
        assertThat(parser.parse(collectionWith("{ \"mode\": \"file\", \"file\": {} }"))
                .operations().getFirst().requestSchema()).isNull();
        assertThat(parser.parse(collectionWith("{ \"mode\": \"raw\", \"raw\": \"<xml/>\" }"))
                .operations().getFirst().requestSchema()).isNull();
    }

    @Test
    void leavesOpenApiOnlyFilterDimensionsUnset() {
        var create = operation(parser.parse(COLLECTION).operations(), "invoices/create-invoice");

        assertThat(create.tags()).isNull();
        assertThat(create.deprecated()).isNull();
    }

    @Test
    void detectsTheDeclaredAuthTypeWithoutReadingCredentials() {
        assertThat(parser.parse(COLLECTION).detectedAuthMethod()).isEqualTo(ApiAuthMethod.BEARER_TOKEN);
    }

    @Test
    void mapsEveryRecognizedAuthType() {
        assertThat(authOf("apikey")).isEqualTo(ApiAuthMethod.API_KEY);
        assertThat(authOf("basic")).isEqualTo(ApiAuthMethod.BASIC);
        assertThat(authOf("jwt")).isEqualTo(ApiAuthMethod.BEARER_TOKEN);
        assertThat(authOf("oauth2")).isEqualTo(ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS);
        assertThat(authOf("noauth")).isEqualTo(ApiAuthMethod.NONE);
        assertThat(authOf("hawk")).isNull();
    }

    private ApiAuthMethod authOf(String type) {
        return parser.parse("""
                { %s, "auth": { "type": "%s" },
                  "item": [{ "name": "R", "request": { "method": "GET", "url": { "path": ["x"] } } }] }"""
                .formatted(V21_HEADER, type)).detectedAuthMethod();
    }

    @Test
    void reportsNoAuthMethodWhenTheCollectionDeclaresNone() {
        var parsed = parser.parse("""
                { %s, "item": [{ "name": "R", "request": { "method": "GET", "url": { "path": ["x"] } } }] }"""
                .formatted(V21_HEADER));

        assertThat(parsed.detectedAuthMethod()).isNull();
    }

    @Test
    void sanitizedContentDropsCredentialValuesAndEventScripts() {
        var sanitized = parser.parse(COLLECTION).sanitizedContent();

        assertThat(sanitized)
                .doesNotContain("SUPER-SECRET")
                .doesNotContain("pm.environment.set")
                .doesNotContain("\"event\"")
                .contains("\"type\":\"bearer\"")
                .contains("Create Invoice");
    }

    @Test
    void sanitizedContentAlsoDropsPerRequestAuthAndNestedFolderScripts() {
        var collection = """
                { %s, "item": [{ "name": "Folder",
                  "event": [{ "listen": "test", "script": { "exec": ["console.log('folder')"] } }],
                  "item": [{ "name": "R", "request": {
                    "method": "GET", "url": { "path": ["x"] },
                    "auth": { "type": "apikey", "apikey": [{ "key": "value", "value": "REQ-SECRET" }] } } }] }] }"""
                .formatted(V21_HEADER);

        var sanitized = parser.parse(collection).sanitizedContent();

        assertThat(sanitized)
                .doesNotContain("REQ-SECRET")
                .doesNotContain("console.log")
                .contains("\"type\":\"apikey\"");
    }

    @Test
    void deduplicatesCollidingOperationIdsDeterministically() {
        var collection = """
                { %s, "item": [
                  { "name": "Get User", "request": { "method": "GET", "url": { "path": ["a"] } } },
                  { "name": "get user", "request": { "method": "GET", "url": { "path": ["b"] } } },
                  { "name": "GET  USER", "request": { "method": "GET", "url": { "path": ["c"] } } }] }"""
                .formatted(V21_HEADER);

        var ops = parser.parse(collection).operations();

        assertThat(ops).extracting(ApiOperation::operationId)
                .containsExactly("get-user", "get-user-2", "get-user-3");
    }

    @Test
    void fallsBackToRawUrlWhenNoPathArrayIsPresent() {
        var collection = """
                { %s, "item": [{ "name": "R", "request": { "method": "GET",
                  "url": { "raw": "https://api.example.com/v2/things?limit=5" } } }] }"""
                .formatted(V21_HEADER);

        assertThat(parser.parse(collection).operations().getFirst().path()).isEqualTo("/v2/things");
    }

    @Test
    void handlesAnObjectValuedDescriptionAndAnEmptyPath() {
        var collection = """
                { %s, "item": [{ "name": "Root", "request": { "method": "GET",
                  "description": { "content": "Root ping" }, "url": { "raw": "{{baseUrl}}" } } }] }"""
                .formatted(V21_HEADER);

        var op = parser.parse(collection).operations().getFirst();

        assertThat(op.summary()).isEqualTo("Root ping");
        assertThat(op.path()).isEqualTo("/");
    }

    @Test
    void defaultsAMissingRequestMethodToGet() {
        var collection = """
                { %s, "item": [{ "name": "R", "request": { "url": { "path": ["x"] } } }] }"""
                .formatted(V21_HEADER);

        var op = parser.parse(collection).operations().getFirst();

        assertThat(op.verb()).isEqualTo("GET");
        assertThat(op.write()).isFalse();
        assertThat(op.summary()).isNull();
    }

    @Test
    void namesAnUnnamedRequestDeterministically() {
        var collection = """
                { %s, "item": [{ "name": "***", "request": { "method": "GET", "url": { "path": ["x"] } } }] }"""
                .formatted(V21_HEADER);

        assertThat(parser.parse(collection).operations().getFirst().operationId()).isEqualTo("request");
    }

    @Test
    void rejectsBlankInput() {
        assertThatThrownBy(() -> parser.parse("  "))
                .isInstanceOf(ApiSchemaParseException.class)
                .hasMessageContaining("Empty");
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(ApiSchemaParseException.class);
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> parser.parse("{ not json"))
                .isInstanceOf(ApiSchemaParseException.class)
                .hasMessageContaining("Invalid Postman collection JSON");
    }

    @Test
    void rejectsANonObjectDocument() {
        assertThatThrownBy(() -> parser.parse("[]"))
                .isInstanceOf(ApiSchemaParseException.class)
                .hasMessageContaining("must be a JSON object");
    }

    @Test
    void rejectsACollectionV1Export() {
        var v1 = """
                { "id": "abc", "name": "Legacy", "order": [],
                  "requests": [{ "method": "GET", "url": "https://api.example.com/v1/things" }] }""";

        assertThatThrownBy(() -> parser.parse(v1))
                .isInstanceOf(ApiSchemaParseException.class)
                .hasMessageContaining("Collection v2.1");
    }

    @Test
    void acceptsAV20Export() {
        var v20 = """
                { "info": { "name": "X", "schema":
                    "https://schema.getpostman.com/json/collection/v2.0.0/collection.json" },
                  "item": [{ "name": "R", "request": { "method": "GET", "url": { "path": ["x"] } } }] }""";

        assertThat(parser.parse(v20).operations()).hasSize(1);
    }

    @Test
    void rejectsACollectionWithNoRequests() {
        var empty = """
                { %s, "item": [{ "name": "Empty folder", "item": [] }] }""".formatted(V21_HEADER);

        assertThatThrownBy(() -> parser.parse(empty))
                .isInstanceOf(ApiSchemaParseException.class)
                .hasMessageContaining("no requests");
    }

    @Test
    void rejectsAnOversizedDocument() {
        var oversized = "{\"info\":{\"schema\":\"/v2.1/\"},\"pad\":\"" + "x".repeat(5 * 1024 * 1024) + "\"}";

        assertThatThrownBy(() -> parser.parse(oversized))
                .isInstanceOf(ApiSchemaParseException.class)
                .hasMessageContaining("maximum allowed size");
    }

    @Test
    void rejectsACollectionExceedingTheOperationCap() {
        var item = "{ \"name\": \"R\", \"request\": { \"method\": \"GET\", \"url\": { \"path\": [\"x\"] } } }";
        var many = "{ %s, \"item\": [%s] }".formatted(V21_HEADER,
                String.join(",", java.util.Collections.nCopies(2001, item)));

        assertThatThrownBy(() -> parser.parse(many))
                .isInstanceOf(ApiSchemaParseException.class)
                .hasMessageContaining("more than 2000");
    }
}
