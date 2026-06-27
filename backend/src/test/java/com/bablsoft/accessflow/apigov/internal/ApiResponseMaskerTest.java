package com.bablsoft.accessflow.apigov.internal;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseMaskerTest {

    private final ApiResponseMasker masker = new ApiResponseMasker(JsonMapper.builder().build());

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
}
