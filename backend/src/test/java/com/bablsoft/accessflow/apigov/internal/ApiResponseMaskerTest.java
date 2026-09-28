package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiMaskingMatcherType;
import com.bablsoft.accessflow.apigov.api.ResolvedApiMask;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseMaskerTest {

    private final ApiResponseMasker masker = new ApiResponseMasker(JsonMapper.builder().build());

    private static ResolvedApiMask mask(ApiMaskingMatcherType type, String fieldRef,
                                        MaskingStrategy strategy, Map<String, String> params) {
        return new ResolvedApiMask(UUID.randomUUID(), type, null, fieldRef, strategy, params);
    }

    @Test
    void masksNestedDotPathLeaf() {
        var body = "{\"user\":{\"name\":\"Ada\",\"email\":\"ada@example.com\"}}";

        var masked = masker.mask(body, List.of("user.email"));

        assertThat(masked).contains("\"name\":\"Ada\"").contains("\"email\":\"***\"");
    }

    @Test
    void masksAcrossArrayElements() {
        var body = "{\"items\":[{\"ssn\":\"111\"},{\"ssn\":\"222\"}]}";

        var masked = masker.mask(body, List.of("items.ssn"));

        assertThat(masked).contains("\"ssn\":\"***\"").doesNotContain("111").doesNotContain("222");
    }

    @Test
    void masksWholeSubtreeWhenPathTargetsObject() {
        var body = "{\"profile\":{\"a\":\"1\",\"b\":\"2\"}}";

        var masked = masker.mask(body, List.of("profile"));

        assertThat(masked).contains("\"a\":\"***\"").contains("\"b\":\"***\"");
    }

    @Test
    void nonJsonBodyIsReturnedUnchanged() {
        assertThat(masker.mask("plain text", List.of("a"))).isEqualTo("plain text");
    }

    @Test
    void emptyPathsReturnBodyUnchanged() {
        var body = "{\"a\":\"1\"}";
        assertThat(masker.mask(body, List.of())).isEqualTo(body);
        assertThat(masker.mask(body, null)).isEqualTo(body);
    }

    @Test
    void jsonPathAppliesPartialStrategy() {
        var body = "{\"user\":{\"ssn\":\"123456789\"}}";

        var masked = masker.mask(body, "application/json", List.of(
                mask(ApiMaskingMatcherType.JSON_PATH, "user.ssn", MaskingStrategy.PARTIAL,
                        Map.of("visible_suffix", "4"))));

        assertThat(masked).contains("6789").doesNotContain("12345");
    }

    @Test
    void schemaFieldBehavesAsJsonDotPath() {
        var body = "{\"email\":\"ada@example.com\"}";

        var masked = masker.mask(body, "application/json", List.of(
                new ResolvedApiMask(UUID.randomUUID(), ApiMaskingMatcherType.SCHEMA_FIELD,
                        "getUser", "email", MaskingStrategy.FULL, Map.of())));

        assertThat(masked).contains("\"email\":\"***\"");
    }

    @Test
    void regexMasksCapturingGroup() {
        var body = "{\"card\":\"4111111111111111\"}";

        var masked = masker.mask(body, "application/json", List.of(
                mask(ApiMaskingMatcherType.REGEX, "\"card\":\"(\\d+)\"", MaskingStrategy.FULL, Map.of())));

        assertThat(masked).contains("\"card\":\"***\"").doesNotContain("4111");
    }

    @Test
    void regexMasksWholeMatchWhenNoGroup() {
        var body = "token=abc123 end";

        var masked = masker.mask(body, "text/plain", List.of(
                mask(ApiMaskingMatcherType.REGEX, "abc123", MaskingStrategy.FULL, Map.of())));

        assertThat(masked).isEqualTo("token=*** end");
    }

    @Test
    void xmlPathMasksMatchedElement() {
        var body = "<account><ssn>111-22-3333</ssn><name>Ada</name></account>";

        var masked = masker.mask(body, "application/xml", List.of(
                mask(ApiMaskingMatcherType.XML_PATH, "//ssn", MaskingStrategy.FULL, Map.of())));

        assertThat(masked).contains("<ssn>***</ssn>").contains("<name>Ada</name>");
    }

    @Test
    void xmlDetectedByLeadingAngleBracketWithoutContentType() {
        var body = "<account><ssn>111</ssn></account>";

        var masked = masker.mask(body, null, List.of(
                mask(ApiMaskingMatcherType.XML_PATH, "//ssn", MaskingStrategy.FULL, Map.of())));

        assertThat(masked).contains("<ssn>***</ssn>");
    }

    @Test
    void invalidRegexIsSkipped() {
        var body = "{\"a\":\"1\"}";

        var masked = masker.mask(body, "application/json", List.of(
                mask(ApiMaskingMatcherType.REGEX, "[unclosed", MaskingStrategy.FULL, Map.of())));

        assertThat(masked).contains("\"a\":\"1\"");
    }

    @Test
    void unparseableXmlIsReturnedUnchanged() {
        var body = "<broken><ssn>1</ssn>";

        var masked = masker.mask(body, "application/xml", List.of(
                mask(ApiMaskingMatcherType.XML_PATH, "//ssn", MaskingStrategy.FULL, Map.of())));

        assertThat(masked).isEqualTo(body);
    }

    @Test
    void nullAndEmptyMasksReturnBodyUnchanged() {
        var body = "{\"a\":\"1\"}";
        assertThat(masker.mask(body, "application/json", null)).isEqualTo(body);
        assertThat(masker.mask(body, "application/json", List.of())).isEqualTo(body);
        assertThat(masker.mask(null, "application/json",
                List.of(mask(ApiMaskingMatcherType.JSON_PATH, "a", MaskingStrategy.FULL, Map.of())))).isNull();
    }

    @Test
    void nullifyYieldsJsonNull() {
        var body = "{\"user\":{\"ssn\":\"123456789\"}}";

        var masked = masker.mask(body, "application/json", List.of(
                mask(ApiMaskingMatcherType.JSON_PATH, "user.ssn", MaskingStrategy.NULLIFY, Map.of())));

        assertThat(masked).contains("\"ssn\":null").doesNotContain("123456789");
    }

    @Test
    void nullifyInsideRegexBodyBecomesEmptyText() {
        var body = "token=abc123 end";

        var masked = masker.mask(body, "text/plain", List.of(
                mask(ApiMaskingMatcherType.REGEX, "abc123", MaskingStrategy.NULLIFY, Map.of())));

        assertThat(masked).isEqualTo("token= end");
    }

    @Test
    void nullifyInsideRegexCapturingGroupKeepsSurroundingText() {
        var body = "{\"card\":\"4111111111111111\"}";

        var masked = masker.mask(body, "text/plain", List.of(
                mask(ApiMaskingMatcherType.REGEX, "\"card\":\"(\\d+)\"", MaskingStrategy.NULLIFY,
                        Map.of())));

        assertThat(masked).isEqualTo("{\"card\":\"\"}");
    }

    @Test
    void nullifyEmptiesXmlElement() {
        var body = "<user><ssn>123456789</ssn></user>";

        var masked = masker.mask(body, "application/xml", List.of(
                mask(ApiMaskingMatcherType.XML_PATH, "//ssn", MaskingStrategy.NULLIFY, Map.of())));

        assertThat(masked).doesNotContain("123456789");
    }

    @Test
    void extremeNumericValueInXmlKeepsEveryOtherFieldMasked() {
        var body = "<user><ssn>123456789</ssn><salary>1E+999999999</salary></user>";

        var masked = masker.mask(body, "application/xml", List.of(
                mask(ApiMaskingMatcherType.XML_PATH, "//salary", MaskingStrategy.NUMERIC_BUCKET,
                        Map.of("bucket_size", "10000")),
                mask(ApiMaskingMatcherType.XML_PATH, "//ssn", MaskingStrategy.FULL, Map.of())));

        assertThat(masked).doesNotContain("123456789").doesNotContain("1E+999999999");
    }

    @Test
    void keepFirstAppliesToJsonLeaf() {
        var body = "{\"phone\":\"0912345678\"}";

        var masked = masker.mask(body, "application/json", List.of(
                mask(ApiMaskingMatcherType.JSON_PATH, "phone", MaskingStrategy.KEEP_FIRST,
                        Map.of("visible_prefix", "4"))));

        assertThat(masked).contains("\"phone\":\"0912******\"");
    }

    @Test
    void legacyRestrictedFieldHelperFullMasks() {
        var body = "{\"ssn\":\"123\"}";

        var masked = masker.mask(body, "application/json",
                List.of(ResolvedApiMask.legacyRestrictedField("ssn")));

        assertThat(masked).contains("\"ssn\":\"***\"");
    }
}
