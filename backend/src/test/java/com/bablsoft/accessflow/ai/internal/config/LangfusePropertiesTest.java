package com.bablsoft.accessflow.ai.internal.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LangfusePropertiesTest {

    @Test
    void appliesDefaultsWhenAllNull() {
        var props = new LangfuseProperties(null, null, null, null);

        assertThat(props.defaultHost()).isEqualTo(URI.create("https://cloud.langfuse.com/"));
        assertThat(props.promptCacheTtl()).isEqualTo(Duration.ofSeconds(60));
        assertThat(props.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void normalizesDefaultHostTrailingSlash() {
        var props = new LangfuseProperties(URI.create("https://lf.example.com"), null, null, null);

        assertThat(props.defaultHost().toString()).isEqualTo("https://lf.example.com/");
    }

    @Test
    void keepsExistingTrailingSlashAndExplicitValues() {
        var props = new LangfuseProperties(
                URI.create("https://lf.example.com/"),
                Duration.ofSeconds(30),
                Duration.ofSeconds(2),
                Duration.ofSeconds(8));

        assertThat(props.defaultHost().toString()).isEqualTo("https://lf.example.com/");
        assertThat(props.promptCacheTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(props.requestTimeout()).isEqualTo(Duration.ofSeconds(8));
    }
}
