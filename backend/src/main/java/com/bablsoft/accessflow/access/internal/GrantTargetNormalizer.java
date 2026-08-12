package com.bablsoft.accessflow.access.internal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Folds grant-scope identifiers so a grant's allow-list can be compared against what audit says was
 * actually exercised (#625). Grants store table names trim-only and case-preserved; the SQL parser
 * stores referenced tables ASCII-lowercased with quotes stripped and possibly schema-qualified.
 *
 * <p>This mirrors {@code proxy.internal.SqlParserServiceImpl.normalizeIdentifier} and
 * {@code compliance.internal.TableNameNormalizer}, which mirror each other for the same reason: the
 * Spring Modulith boundary forbids importing another module's {@code internal} types, so each module
 * that needs the fold owns a copy — exactly as each module owns its own RFC-4180 CSV escaper.
 *
 * <p>API-connector operation ids need no schema handling, but folding them through the same path
 * costs nothing and keeps one comparison rule for both grant kinds.
 */
final class GrantTargetNormalizer {

    private GrantTargetNormalizer() {
    }

    /** Strips quote characters, trims, and ASCII-lowercases. */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        var stripped = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"' || c == '`' || c == '[' || c == ']') {
                continue;
            }
            stripped.append(c);
        }
        return stripped.toString().trim().toLowerCase(Locale.ROOT);
    }

    /** Normalizes a list, dropping blanks and duplicates while preserving first-seen order. */
    static Set<String> normalizeAll(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        var out = new LinkedHashSet<String>(raw.size());
        for (var value : raw) {
            var normalized = normalize(value);
            if (!normalized.isEmpty()) {
                out.add(normalized);
            }
        }
        return out;
    }

    /** The bare identifier: the segment after the last {@code .} of an already-normalized value. */
    static String suffix(String normalized) {
        int dot = normalized.lastIndexOf('.');
        return dot < 0 ? normalized : normalized.substring(dot + 1);
    }

    /**
     * True when a used identifier refers to a granted one. Exact match wins; when either side is
     * unqualified the bare names are compared, so an {@code orders} allow-list entry matches a
     * {@code public.orders} reference. Two differently-qualified names never match, which keeps
     * {@code sales.orders} from counting as use of a {@code public.orders} grant.
     */
    static boolean matches(String usedNormalized, String grantedNormalized) {
        if (usedNormalized.isEmpty() || grantedNormalized.isEmpty()) {
            return false;
        }
        if (usedNormalized.equals(grantedNormalized)) {
            return true;
        }
        boolean usedBare = usedNormalized.indexOf('.') < 0;
        boolean grantedBare = grantedNormalized.indexOf('.') < 0;
        if (usedBare || grantedBare) {
            return suffix(usedNormalized).equals(suffix(grantedNormalized));
        }
        return false;
    }

    /**
     * How many of {@code granted} were exercised by {@code used}. Counts granted entries, not used
     * ones, so a query touching a table outside the allow-list (possible for a
     * {@code QUERY_ADMIN} holder, whose submissions bypass the per-grant check) can never push the
     * count above the grant's own scope.
     */
    static int countGrantedExercised(Set<String> granted, Set<String> used) {
        if (granted.isEmpty() || used.isEmpty()) {
            return 0;
        }
        int exercised = 0;
        for (var grantedTarget : granted) {
            for (var usedTarget : used) {
                if (matches(usedTarget, grantedTarget)) {
                    exercised++;
                    break;
                }
            }
        }
        return exercised;
    }
}
