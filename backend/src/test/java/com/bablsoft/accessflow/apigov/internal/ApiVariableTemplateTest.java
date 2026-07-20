package com.bablsoft.accessflow.apigov.internal;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiVariableTemplateTest {

    /** Resolves variable references from a fixed map; request references are unavailable. */
    private static String renderVars(String template, Map<String, String> values, boolean strict) {
        return ApiVariableTemplate.render(template,
                ref -> ref.isVariable() ? values.get(ref.key()) : null,
                strict ? ApiVariableTemplate.EXPRESSION_STRICT_SCOPES
                        : ApiVariableTemplate.SUBSTITUTION_STRICT_SCOPES);
    }

    @Nested
    class Grammar {

        @Test
        void substitutesBareReference() {
            assertThat(renderVars("Bearer {{token}}", Map.of("token", "abc"), false))
                    .isEqualTo("Bearer abc");
        }

        @Test
        void substitutesQualifiedReference() {
            assertThat(renderVars("{{var.token}}", Map.of("token", "abc"), false)).isEqualTo("abc");
        }

        @Test
        void toleratesInnerWhitespace() {
            assertThat(renderVars("{{  token  }}", Map.of("token", "abc"), false)).isEqualTo("abc");
        }

        @Test
        void substitutesEveryOccurrence() {
            assertThat(renderVars("{{a}}-{{b}}-{{a}}", Map.of("a", "1", "b", "2"), false))
                    .isEqualTo("1-2-1");
        }

        @Test
        void returnsNullAndEmptyUnchanged() {
            assertThat(renderVars(null, Map.of(), true)).isNull();
            assertThat(renderVars("", Map.of(), true)).isEmpty();
        }

        @Test
        void leavesTextWithoutPlaceholdersUntouched() {
            assertThat(renderVars("{ \"a\": 1 }", Map.of("a", "x"), true)).isEqualTo("{ \"a\": 1 }");
        }

        @Test
        void treatsValueAsLiteralReplacementNotRegex() {
            // A digest is hex, but a CONSTANT can hold anything — $1 and \n must not be interpreted.
            assertThat(renderVars("[{{v}}]", Map.of("v", "$1\\n"), false)).isEqualTo("[$1\\n]");
        }
    }

    @Nested
    class Strictness {

        @Test
        void leavesUnknownBareReferenceLiteral() {
            // Bodies legitimately carry braces from unrelated templating; erroring would break them.
            assertThat(renderVars("{{handlebars}}", Map.of(), true)).isEqualTo("{{handlebars}}");
        }

        @Test
        void throwsOnUnknownQualifiedReferenceWhenStrict() {
            assertThatThrownBy(() -> renderVars("{{var.typo}}", Map.of(), true))
                    .isInstanceOf(ApiVariableTemplate.UnresolvedReferenceException.class)
                    .satisfies(ex -> assertThat(
                            ((ApiVariableTemplate.UnresolvedReferenceException) ex).reference().key())
                            .isEqualTo("typo"));
        }

        @Test
        void throwsOnUnresolvedRequestReferenceInExpressionScope() {
            assertThatThrownBy(() -> ApiVariableTemplate.render("{{request.nope}}", ref -> null,
                    ApiVariableTemplate.EXPRESSION_STRICT_SCOPES))
                    .isInstanceOf(ApiVariableTemplate.UnresolvedReferenceException.class);
        }

        @Test
        void leavesRequestReferenceLiteralAtSubstitutionSite() {
            // At a substitution site there is no expression being evaluated, so request.* is
            // meaningless rather than wrong — a body may contain such text for its own reasons.
            assertThat(ApiVariableTemplate.render("{{request.body}}", ref -> null,
                    ApiVariableTemplate.SUBSTITUTION_STRICT_SCOPES)).isEqualTo("{{request.body}}");
        }

        @Test
        void leavesForeignNamespaceLiteralEvenWhenStrict() {
            assertThat(renderVars("{{aws.region}}", Map.of(), true)).isEqualTo("{{aws.region}}");
        }
    }

    @Nested
    class SecurityProperties {

        /**
         * The containment property that makes per-request overrides safe. A submitter may override
         * an overridable variable with arbitrary text; if that text were re-scanned, an override of
         * "{{signingKey}}" would expand into the secret-derived value of another variable. Rendering
         * is single-pass, so it stays literal.
         */
        @Test
        void doesNotRecursivelyExpandSubstitutedValues() {
            var values = Map.of("userSupplied", "{{signingKey}}", "signingKey", "s3cr3t");

            assertThat(renderVars("{{userSupplied}}", values, false)).isEqualTo("{{signingKey}}");
        }

        @Test
        void doesNotExpandQualifiedReferenceHiddenInsideAValue() {
            var values = Map.of("userSupplied", "{{var.signingKey}}", "signingKey", "s3cr3t");

            assertThat(renderVars("{{userSupplied}}", values, true)).isEqualTo("{{var.signingKey}}");
        }
    }

    @Nested
    class ReferenceExtraction {

        @Test
        void collectsBareAndQualifiedNamesInFirstAppearanceOrder() {
            assertThat(ApiVariableTemplate.variableReferences("{{b}}{{var.a}}{{b}}"))
                    .containsExactly("b", "a");
        }

        @Test
        void ignoresRequestAndForeignNamespaces() {
            assertThat(ApiVariableTemplate.variableReferences("{{request.body}}{{aws.region}}"))
                    .isEmpty();
        }

        @Test
        void strictReferencesCollectsOnlyQualifiedForm() {
            assertThat(ApiVariableTemplate.strictVariableReferences("{{a}}{{var.b}}"))
                    .containsExactly("b");
        }
    }

    @Nested
    class NamePattern {

        @Test
        void acceptsLetterLedAlphanumericNames() {
            assertThat(ApiVariableTemplate.NAME_PATTERN.matcher("sig_1").matches()).isTrue();
            assertThat(ApiVariableTemplate.NAME_PATTERN.matcher("A").matches()).isTrue();
        }

        @Test
        void rejectsNamesThatCouldShadowANamespaceOrBreakTheGrammar() {
            assertThat(ApiVariableTemplate.NAME_PATTERN.matcher("request.body").matches()).isFalse();
            assertThat(ApiVariableTemplate.NAME_PATTERN.matcher("1sig").matches()).isFalse();
            assertThat(ApiVariableTemplate.NAME_PATTERN.matcher("").matches()).isFalse();
            assertThat(ApiVariableTemplate.NAME_PATTERN.matcher("a-b").matches()).isFalse();
            assertThat(ApiVariableTemplate.NAME_PATTERN.matcher("a".repeat(65)).matches()).isFalse();
        }
    }
}
