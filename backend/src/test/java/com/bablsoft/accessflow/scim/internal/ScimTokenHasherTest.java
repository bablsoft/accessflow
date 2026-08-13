package com.bablsoft.accessflow.scim.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScimTokenHasherTest {

    @Test
    void generateProducesPrefixedHighEntropyTokens() {
        var token = ScimTokenHasher.generate();

        assertThat(token).startsWith("af_scim_");
        assertThat(token.length()).isGreaterThan(40);
        assertThat(ScimTokenHasher.generate()).isNotEqualTo(token);
    }

    @Test
    void hashIsDeterministicSha256Hex() {
        var hash = ScimTokenHasher.hash("af_scim_test");

        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
        assertThat(ScimTokenHasher.hash("af_scim_test")).isEqualTo(hash);
        assertThat(ScimTokenHasher.hash("af_scim_other")).isNotEqualTo(hash);
    }

    @Test
    void prefixOfTruncatesToTwelveChars() {
        assertThat(ScimTokenHasher.prefixOf("af_scim_AbCdEfGh")).isEqualTo("af_scim_AbCd");
        assertThat(ScimTokenHasher.prefixOf("short")).isEqualTo("short");
        assertThat(ScimTokenHasher.prefixOf(null)).isEmpty();
    }

    @Test
    void hasExpectedShapeRequiresPrefix() {
        assertThat(ScimTokenHasher.hasExpectedShape(ScimTokenHasher.generate())).isTrue();
        assertThat(ScimTokenHasher.hasExpectedShape("af_notscim")).isFalse();
        assertThat(ScimTokenHasher.hasExpectedShape("af_scim_")).isFalse();
        assertThat(ScimTokenHasher.hasExpectedShape(null)).isFalse();
    }
}
