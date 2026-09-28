package com.bablsoft.accessflow.workflow.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionHookViewTest {

    private static DecisionHookView view(String url) {
        return new DecisionHookView(UUID.randomUUID(), UUID.randomUUID(), null, "OPA", url, 2000,
                false, true, true, 0L, Instant.now(), Instant.now());
    }

    @Test
    void theOriginDropsThePathAndQuery() {
        assertThat(view("https://opa.example.com/v1/data?token=secret").endpointOrigin())
                .isEqualTo("https://opa.example.com");
        assertThat(view("http://10.0.0.5:8181/v1/data").endpointOrigin())
                .isEqualTo("http://10.0.0.5:8181");
    }

    @Test
    void anUnparseableOrHostlessUrlHasNoOrigin() {
        assertThat(view("not a url").endpointOrigin()).isNull();
        assertThat(view("/relative").endpointOrigin()).isNull();
    }
}
