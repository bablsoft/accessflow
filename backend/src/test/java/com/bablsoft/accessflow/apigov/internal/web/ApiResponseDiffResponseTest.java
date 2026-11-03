package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiResponseDiffEntry;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffFormat;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffKind;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffView;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseDiffResponseTest {

    // Mirrors spring.jackson in application.yml: snake_case names and non_null inclusion.
    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @Test
    void nullDeltasAndAbsentSidesAreAlwaysSerialized() {
        var view = new ApiResponseDiffView(UUID.randomUUID(), UUID.randomUUID(), ApiResponseDiffFormat.JSON,
                200, null, null, null, 1, 0, 0, false, true,
                List.of(new ApiResponseDiffEntry("items[2]", ApiResponseDiffKind.ADDED, null, "4")));

        var json = mapper.readTree(mapper.writeValueAsString(ApiResponseDiffResponse.from(view)));

        assertThat(json.has("duration_ms_delta")).isTrue();
        assertThat(json.get("duration_ms_delta").isNull()).isTrue();
        assertThat(json.get("bytes_delta").isNull()).isTrue();
        assertThat(json.get("status_code_previous").isNull()).isTrue();
        assertThat(json.get("either_response_truncated").asBoolean()).isTrue();
        var entry = json.get("entries").get(0);
        assertThat(entry.get("path").asString()).isEqualTo("items[2]");
        assertThat(entry.get("kind").asString()).isEqualTo("ADDED");
        assertThat(entry.has("before")).isTrue();
        assertThat(entry.get("before").isNull()).isTrue();
        assertThat(entry.get("after").asString()).isEqualTo("4");
    }
}
