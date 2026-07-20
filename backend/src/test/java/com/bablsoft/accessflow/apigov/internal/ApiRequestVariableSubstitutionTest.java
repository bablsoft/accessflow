package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiBodyType;
import com.bablsoft.accessflow.apigov.api.ApiFormField;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.api.ApiVariableTargetType;
import com.bablsoft.accessflow.apigov.api.ResolvedApiVariables;
import com.bablsoft.accessflow.apigov.internal.client.ApiCallRequest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRequestVariableSubstitutionTest {

    private static ApiCallRequest request(Map<String, String> headers, Map<String, String> query,
                                          ApiBodyType bodyType, String body,
                                          List<ApiFormField> formFields) {
        return new ApiCallRequest(ApiProtocol.REST, "https://api.example.com", "POST", "/v1/pay",
                headers, query, bodyType, body, "application/json", formFields, null, 30_000,
                1_000_000L, "createPayment");
    }

    private static ResolvedApiVariables resolved(Map<String, String> values) {
        return new ResolvedApiVariables(values, List.of());
    }

    @Nested
    class CanonicalQuery {

        @Test
        void isEmptyForNoParameters() {
            assertThat(ApiRequestVariableSubstitution.canonicalQuery(Map.of())).isEmpty();
            assertThat(ApiRequestVariableSubstitution.canonicalQuery(null)).isEmpty();
        }

        /** Sorted rather than wire order, so a signature over it reproduces on both sides. */
        @Test
        void sortsKeysRegardlessOfInsertionOrder() {
            var params = new LinkedHashMap<String, String>();
            params.put("z", "1");
            params.put("a", "2");

            assertThat(ApiRequestVariableSubstitution.canonicalQuery(params)).isEqualTo("a=2&z=1");
        }

        @Test
        void percentEncodesKeysAndValues() {
            assertThat(ApiRequestVariableSubstitution.canonicalQuery(Map.of("a b", "c&d")))
                    .isEqualTo("a+b=c%26d");
        }

        @Test
        void rendersANullValueAsEmpty() {
            var params = new LinkedHashMap<String, String>();
            params.put("a", null);

            assertThat(ApiRequestVariableSubstitution.canonicalQuery(params)).isEqualTo("a=");
        }
    }

    @Nested
    class BodyForContext {

        @Test
        void rawBodyIsUsedVerbatim() {
            var req = request(Map.of(), Map.of(), ApiBodyType.RAW, "{\"a\":1}", List.of());

            assertThat(ApiRequestVariableSubstitution.bodyForContext(req)).isEqualTo("{\"a\":1}");
        }

        @Test
        void noBodyIsEmpty() {
            var req = request(Map.of(), Map.of(), ApiBodyType.NONE, null, List.of());

            assertThat(ApiRequestVariableSubstitution.bodyForContext(req)).isEmpty();
        }

        @Test
        void formUrlencodedIsCanonicalized() {
            var fields = List.of(
                    new ApiFormField("z", ApiFormField.ApiFormFieldType.TEXT, "1", null, null),
                    new ApiFormField("a", ApiFormField.ApiFormFieldType.TEXT, "2", null, null));
            var req = request(Map.of(), Map.of(), ApiBodyType.FORM_URLENCODED, null, fields);

            assertThat(ApiRequestVariableSubstitution.bodyForContext(req)).isEqualTo("a=2&z=1");
        }

        /** Multipart boundaries are generated per send, so no signature over one is reproducible. */
        @Test
        void formDataIsEmptyBecauseItIsNotReproducible() {
            var fields = List.of(new ApiFormField("a", ApiFormField.ApiFormFieldType.TEXT, "1", null, null));
            var req = request(Map.of(), Map.of(), ApiBodyType.FORM_DATA, null, fields);

            assertThat(ApiRequestVariableSubstitution.bodyForContext(req)).isEmpty();
        }
    }

    @Nested
    class Substitution {

        @Test
        void returnsTheRequestUnchangedWhenNothingResolved() {
            var req = request(Map.of("X", "{{v}}"), Map.of(), ApiBodyType.RAW, "{{v}}", List.of());

            assertThat(ApiRequestVariableSubstitution.apply(req, ResolvedApiVariables.empty()))
                    .isSameAs(req);
            assertThat(ApiRequestVariableSubstitution.apply(req, null)).isSameAs(req);
        }

        @Test
        void substitutesHeaderValuesPathAndQueryValues() {
            var req = request(Map.of("X-Signature", "{{sig}}"), Map.of("nonce", "{{sig}}"),
                    ApiBodyType.RAW, null, List.of());

            var out = ApiRequestVariableSubstitution.apply(req, resolved(Map.of("sig", "abc123")));

            assertThat(out.headers()).containsEntry("X-Signature", "abc123");
            assertThat(out.queryParams()).containsEntry("nonce", "abc123");
        }

        @Test
        void substitutesThePath() {
            var req = new ApiCallRequest(ApiProtocol.REST, "https://x", "GET", "/v1/{{tenant}}/items",
                    Map.of(), Map.of(), ApiBodyType.NONE, null, null, List.of(), null, 1, 1L, null);

            assertThat(ApiRequestVariableSubstitution.apply(req, resolved(Map.of("tenant", "acme")))
                    .path()).isEqualTo("/v1/acme/items");
        }

        @Test
        void substitutesARawBody() {
            var req = request(Map.of(), Map.of(), ApiBodyType.RAW,
                    "{\"data\":\"x\",\"HMAC\":\"{{signature}}\"}", List.of());

            assertThat(ApiRequestVariableSubstitution.apply(req, resolved(Map.of("signature", "deadbeef")))
                    .body()).isEqualTo("{\"data\":\"x\",\"HMAC\":\"deadbeef\"}");
        }

        /** Header names must stay fixed — a variable-named header could not be reviewed. */
        @Test
        void doesNotSubstituteHeaderNamesOrQueryKeys() {
            var req = request(Map.of("{{name}}", "v"), Map.of("{{name}}", "v"),
                    ApiBodyType.NONE, null, List.of());

            var out = ApiRequestVariableSubstitution.apply(req, resolved(Map.of("name", "X-Evil")));

            assertThat(out.headers()).containsKey("{{name}}").doesNotContainKey("X-Evil");
            assertThat(out.queryParams()).containsKey("{{name}}");
        }

        /** The connector's target host is admin config; a variable there would be an SSRF pivot. */
        @Test
        void doesNotSubstituteTheBaseUrl() {
            var req = new ApiCallRequest(ApiProtocol.REST, "https://{{host}}/api", "GET", "/x",
                    Map.of(), Map.of(), ApiBodyType.NONE, null, null, List.of(), null, 1, 1L, null);

            assertThat(ApiRequestVariableSubstitution.apply(req, resolved(Map.of("host", "evil.test")))
                    .baseUrl()).isEqualTo("https://{{host}}/api");
        }

        @Test
        void doesNotSubstituteABinaryBodyBecauseItIsBase64() {
            var req = request(Map.of(), Map.of(), ApiBodyType.BINARY, "e3t2fX0=", List.of());

            assertThat(ApiRequestVariableSubstitution.apply(req, resolved(Map.of("v", "x"))).body())
                    .isEqualTo("e3t2fX0=");
        }

        @Test
        void substitutesTextFormPartsButNotFileParts() {
            var fields = List.of(
                    new ApiFormField("sig", ApiFormField.ApiFormFieldType.TEXT, "{{v}}", null, null),
                    new ApiFormField("doc", ApiFormField.ApiFormFieldType.FILE, "{{v}}", "a.pdf",
                            "application/pdf"));
            var req = request(Map.of(), Map.of(), ApiBodyType.FORM_DATA, null, fields);

            var out = ApiRequestVariableSubstitution.apply(req, resolved(Map.of("v", "abc")));

            assertThat(out.formFields().get(0).value()).isEqualTo("abc");
            assertThat(out.formFields().get(1).value()).isEqualTo("{{v}}");
        }

        @Test
        void leavesUnknownBarePlaceholdersLiteral() {
            var req = request(Map.of(), Map.of(), ApiBodyType.RAW, "{{unrelated}}", List.of());

            assertThat(ApiRequestVariableSubstitution.apply(req, resolved(Map.of("v", "x"))).body())
                    .isEqualTo("{{unrelated}}");
        }
    }

    @Nested
    class Injections {

        @Test
        void appliesAHeaderTargetAfterSubstitution() {
            var req = request(Map.of("X-Sig", "placeholder"), Map.of(), ApiBodyType.NONE, null, List.of());
            var resolved = new ResolvedApiVariables(Map.of("sig", "abc"),
                    List.of(new ResolvedApiVariables.ApiVariableInjection(
                            ApiVariableTargetType.HEADER, "X-Sig", "abc")));

            assertThat(ApiRequestVariableSubstitution.apply(req, resolved).headers())
                    .containsEntry("X-Sig", "abc");
        }

        @Test
        void appliesAQueryTarget() {
            var req = request(Map.of(), Map.of(), ApiBodyType.NONE, null, List.of());
            var resolved = new ResolvedApiVariables(Map.of("ts", "12345"),
                    List.of(new ResolvedApiVariables.ApiVariableInjection(
                            ApiVariableTargetType.QUERY, "timestamp", "12345")));

            assertThat(ApiRequestVariableSubstitution.apply(req, resolved).queryParams())
                    .containsEntry("timestamp", "12345");
        }

        /** A placeholder in the body and a header target on the same variable must both apply. */
        @Test
        void aPlaceholderAndATargetCoexist() {
            var req = request(Map.of(), Map.of(), ApiBodyType.RAW, "sig={{sig}}", List.of());
            var resolved = new ResolvedApiVariables(Map.of("sig", "abc"),
                    List.of(new ResolvedApiVariables.ApiVariableInjection(
                            ApiVariableTargetType.HEADER, "X-Sig", "abc")));

            var out = ApiRequestVariableSubstitution.apply(req, resolved);

            assertThat(out.body()).isEqualTo("sig=abc");
            assertThat(out.headers()).containsEntry("X-Sig", "abc");
        }
    }
}
