package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiVariableTargetType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiVariableTargetsTest {

    @Test
    void parsesAHeaderTarget() {
        var target = ApiVariableTargets.parse("header:X-Signature");

        assertThat(target).isNotNull();
        assertThat(target.type()).isEqualTo(ApiVariableTargetType.HEADER);
        assertThat(target.key()).isEqualTo("X-Signature");
    }

    @Test
    void parsesAQueryTarget() {
        var target = ApiVariableTargets.parse("query:signature");

        assertThat(target.type()).isEqualTo(ApiVariableTargetType.QUERY);
        assertThat(target.key()).isEqualTo("signature");
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(ApiVariableTargets.parse("  header:X-Sig  ").key()).isEqualTo("X-Sig");
    }

    @Test
    void treatsBlankAsNoTarget() {
        assertThat(ApiVariableTargets.parse(null)).isNull();
        assertThat(ApiVariableTargets.parse("   ")).isNull();
    }

    @Test
    void returnsNullForAMalformedTarget() {
        assertThat(ApiVariableTargets.parse("body")).isNull();
        assertThat(ApiVariableTargets.parse("cookie:x")).isNull();
        assertThat(ApiVariableTargets.parse("header:")).isNull();
    }

    /**
     * The key charset is RFC 7230's {@code token}, so a target can never smuggle a separator or a
     * control character into the header block.
     */
    @Test
    void rejectsHeaderNamesCarryingSeparatorsOrControlCharacters() {
        assertThat(ApiVariableTargets.isValid("header:X-Sig: evil")).isFalse();
        assertThat(ApiVariableTargets.isValid("header:X-Sig\r\nEvil")).isFalse();
        assertThat(ApiVariableTargets.isValid("header:X Sig")).isFalse();
        assertThat(ApiVariableTargets.isValid("header:" + "x".repeat(129))).isFalse();
    }

    @Test
    void treatsBlankAsValidBecauseNoTargetIsTheCommonCase() {
        assertThat(ApiVariableTargets.isValid(null)).isTrue();
        assertThat(ApiVariableTargets.isValid("")).isTrue();
        assertThat(ApiVariableTargets.isValid("header:X-Sig")).isTrue();
    }

    @Test
    void normalizesBlankToNullAndTrimsOtherwise() {
        assertThat(ApiVariableTargets.normalize("  ")).isNull();
        assertThat(ApiVariableTargets.normalize(null)).isNull();
        assertThat(ApiVariableTargets.normalize(" header:X-Sig ")).isEqualTo("header:X-Sig");
    }

    @Test
    void isCaseInsensitiveOnTheTypePrefix() {
        assertThat(ApiVariableTargets.parse("header:X").type()).isEqualTo(ApiVariableTargetType.HEADER);
    }
}
