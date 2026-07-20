package com.bablsoft.accessflow.apigov.internal;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code {{...}}} placeholder grammar for connector dynamic variables (AF-613).
 *
 * <p>Three reference forms, differing in how an unresolved one is treated:
 * <ul>
 *   <li>{@code {{name}}} — a connector variable, <em>lenient</em>: left literal when unknown.
 *       Request bodies legitimately contain braces from unrelated templating (Handlebars, Jinja,
 *       vendor payload templates), and erroring on every unmatched pair would break real
 *       submissions.</li>
 *   <li>{@code {{var.name}}} — the same variable, <em>strict</em>: an unknown name is an error. This
 *       is the fail-fast spelling for authors who want a typo caught rather than sent.</li>
 *   <li>{@code {{request.*}}} — the evaluation context, strict, and only meaningful inside a
 *       variable's expression. At a substitution site (a header, path, query or body) there is no
 *       request scope, so such a reference is left literal.</li>
 * </ul>
 *
 * <p><strong>Rendering is single-pass and non-recursive.</strong> A substituted value is never
 * re-scanned for further placeholders. This is the primary containment property for per-request
 * overrides: a submitter-supplied override of {@code "{{apiKey}}"} stays those eleven literal
 * characters and can never expand into the value of a secret-bearing variable. It is a security
 * boundary, not a performance choice — see {@code ApiVariableTemplateTest}.
 */
final class ApiVariableTemplate {

    /**
     * A name is a leading letter plus letters/digits/underscore, capped at 64 — deliberately
     * dot-free so a variable can never shadow the {@code request.} or {@code var.} namespace.
     */
    static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_.\\-]*)\\s*}}");

    private static final String VAR_PREFIX = "var.";
    private static final String REQUEST_PREFIX = "request.";

    private ApiVariableTemplate() {
    }

    /** Which namespace a reference addresses, and therefore whether it resolves strictly. */
    enum Scope {
        /** {@code {{name}}} — a variable, resolved leniently. */
        VARIABLE_BARE,
        /** {@code {{var.name}}} — a variable, resolved strictly. */
        VARIABLE_QUALIFIED,
        /** {@code {{request.x}}} — the evaluation context. */
        REQUEST,
        /** Some other dotted token; never resolved, always left literal. */
        FOREIGN
    }

    /**
     * One parsed placeholder. {@code key} is the reference with its namespace prefix stripped, so
     * {@code {{name}}} and {@code {{var.name}}} both carry key {@code "name"}.
     */
    record Reference(String raw, Scope scope, String key) {

        boolean isVariable() {
            return scope == Scope.VARIABLE_BARE || scope == Scope.VARIABLE_QUALIFIED;
        }
    }

    /** Signals a strict reference that could not be resolved. Carries the reference, never a value. */
    static final class UnresolvedReferenceException extends RuntimeException {

        private final transient Reference reference;

        UnresolvedReferenceException(Reference reference) {
            super("Unresolved template reference: " + reference.raw());
            this.reference = reference;
        }

        Reference reference() {
            return reference;
        }
    }

    private static Reference parse(String raw) {
        if (raw.startsWith(VAR_PREFIX)) {
            return new Reference(raw, Scope.VARIABLE_QUALIFIED, raw.substring(VAR_PREFIX.length()));
        }
        if (raw.startsWith(REQUEST_PREFIX)) {
            return new Reference(raw, Scope.REQUEST, raw.substring(REQUEST_PREFIX.length()));
        }
        return raw.indexOf('.') >= 0
                ? new Reference(raw, Scope.FOREIGN, raw)
                : new Reference(raw, Scope.VARIABLE_BARE, raw);
    }

    /** The distinct variable names a template references, in first-appearance order. */
    static Set<String> variableReferences(String template) {
        var names = new LinkedHashSet<String>();
        if (template == null || template.isEmpty()) {
            return names;
        }
        var matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            var ref = parse(matcher.group(1));
            if (ref.isVariable()) {
                names.add(ref.key());
            }
        }
        return names;
    }

    /**
     * The distinct variable names referenced in the strict {@code {{var.name}}} form. An unknown
     * name here is a configuration error; an unknown bare {@code {{name}}} is not.
     */
    static Set<String> strictVariableReferences(String template) {
        var names = new LinkedHashSet<String>();
        if (template == null || template.isEmpty()) {
            return names;
        }
        var matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            var ref = parse(matcher.group(1));
            if (ref.scope() == Scope.VARIABLE_QUALIFIED) {
                names.add(ref.key());
            }
        }
        return names;
    }

    /**
     * The scopes that are strict when rendering a variable's <em>expression</em>: both qualified
     * forms must resolve, since an expression is admin-authored configuration and a typo there is a
     * bug worth surfacing at save time.
     */
    static final Set<Scope> EXPRESSION_STRICT_SCOPES =
            Set.of(Scope.VARIABLE_QUALIFIED, Scope.REQUEST);

    /**
     * The scopes that are strict when rendering a <em>substitution site</em> (a header value, path,
     * query value or body). {@code {{request.*}}} is not strict here: there is no expression being
     * evaluated, so the reference is meaningless rather than wrong, and a body may well contain such
     * text for reasons of its own.
     */
    static final Set<Scope> SUBSTITUTION_STRICT_SCOPES = Set.of(Scope.VARIABLE_QUALIFIED);

    /**
     * Renders {@code template}, replacing each resolvable placeholder exactly once.
     *
     * @param resolver     returns the value for a reference, or {@code null} when it cannot supply
     *                     one. A resolver with no request context simply returns {@code null} for
     *                     {@link Scope#REQUEST} references.
     * @param strictScopes scopes for which an unresolved reference throws rather than staying
     *                     literal. {@link Scope#VARIABLE_BARE} and {@link Scope#FOREIGN} are always
     *                     lenient, whatever this contains.
     * @throws UnresolvedReferenceException for an unresolved reference in a strict scope
     */
    static String render(String template, Function<Reference, String> resolver, Set<Scope> strictScopes) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        var matcher = PLACEHOLDER.matcher(template);
        var out = new StringBuilder(template.length());
        while (matcher.find()) {
            var ref = parse(matcher.group(1));
            var value = ref.scope() == Scope.FOREIGN ? null : resolver.apply(ref);
            if (value == null) {
                if (strictScopes.contains(ref.scope())) {
                    throw new UnresolvedReferenceException(ref);
                }
                // Lenient: emit the placeholder untouched so unrelated templating survives.
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
