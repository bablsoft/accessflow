package com.bablsoft.accessflow.scim.internal.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScimFilterParserTest {

    @Test
    void parsesOktaShapedUserNameFilter() {
        var filter = ScimFilterParser.parse("userName eq \"jane@example.com\"");

        assertThat(filter.attribute()).isEqualTo("username");
        assertThat(filter.value()).isEqualTo("jane@example.com");
    }

    @Test
    void parsesUrnQualifiedAttribute() {
        var filter = ScimFilterParser.parse(
                "urn:ietf:params:scim:schemas:core:2.0:User:userName eq \"x@y.z\"");

        assertThat(filter.attribute()).isEqualTo("username");
    }

    @Test
    void unescapesQuotedValues() {
        var filter = ScimFilterParser.parse("displayName eq \"The \\\"A\\\" Team\"");

        assertThat(filter.value()).isEqualTo("The \"A\" Team");
    }

    @Test
    void nullOrBlankMeansNoFilter() {
        assertThat(ScimFilterParser.parse(null)).isNull();
        assertThat(ScimFilterParser.parse("  ")).isNull();
    }

    @Test
    void rejectsNonEqExpressions() {
        assertThatThrownBy(() -> ScimFilterParser.parse("userName co \"jane\""))
                .isInstanceOf(ScimInvalidFilterException.class);
        assertThatThrownBy(() -> ScimFilterParser.parse(
                "userName eq \"a\" and active eq true"))
                .isInstanceOf(ScimInvalidFilterException.class);
        assertThatThrownBy(() -> ScimFilterParser.parse("active pr"))
                .isInstanceOf(ScimInvalidFilterException.class);
    }

    @Test
    void eqIsCaseInsensitive() {
        assertThat(ScimFilterParser.parse("externalId EQ \"ext-1\"").attribute())
                .isEqualTo("externalid");
    }
}
