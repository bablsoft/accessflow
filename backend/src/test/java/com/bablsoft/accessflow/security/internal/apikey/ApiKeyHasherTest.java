package com.bablsoft.accessflow.security.internal.apikey;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyHasherTest {

    @Test
    void generate_returns_unique_keys_with_expected_prefix() {
        var a = ApiKeyHasher.generate();
        var b = ApiKeyHasher.generate();
        assertThat(a).startsWith(ApiKeyHasher.PREFIX);
        assertThat(b).startsWith(ApiKeyHasher.PREFIX);
        assertThat(a).hasSize(ApiKeyHasher.PREFIX.length() + 43); // 32 bytes base64url-no-padding = 43 chars
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void hash_is_deterministic_and_64_hex_chars() {
        var raw = "af_some-fixed-value";
        var first = ApiKeyHasher.hash(raw);
        var second = ApiKeyHasher.hash(raw);
        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void hash_differs_per_input() {
        assertThat(ApiKeyHasher.hash("af_one")).isNotEqualTo(ApiKeyHasher.hash("af_two"));
    }

    @Test
    void prefixOf_takes_first_12_chars() {
        var raw = ApiKeyHasher.generate();
        assertThat(ApiKeyHasher.prefixOf(raw)).hasSize(ApiKeyHasher.PREFIX_LENGTH);
        assertThat(ApiKeyHasher.prefixOf(raw)).isEqualTo(raw.substring(0, ApiKeyHasher.PREFIX_LENGTH));
    }

    @Test
    void prefixOf_handles_short_input_gracefully() {
        assertThat(ApiKeyHasher.prefixOf(null)).isEmpty();
        assertThat(ApiKeyHasher.prefixOf("af")).isEqualTo("af");
    }

    @Test
    void hasExpectedShape_validates_prefix() {
        assertThat(ApiKeyHasher.hasExpectedShape(null)).isFalse();
        assertThat(ApiKeyHasher.hasExpectedShape("")).isFalse();
        assertThat(ApiKeyHasher.hasExpectedShape("af_")).isFalse();
        assertThat(ApiKeyHasher.hasExpectedShape("Bearer xyz")).isFalse();
        assertThat(ApiKeyHasher.hasExpectedShape("af_xyz")).isTrue();
    }
}
