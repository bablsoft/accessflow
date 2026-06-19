package com.bablsoft.accessflow.compliance.internal;

import java.util.Locale;

/**
 * Normalizes table identifiers so a query's {@code referenced_tables} (stored ASCII-lowercased,
 * quotes stripped, possibly {@code schema.table}) can be matched against data-classification tag
 * table names (stored trim-only, case-preserved). Mirrors
 * {@code proxy.internal.SqlParserServiceImpl.normalizeIdentifier} so both sides fold identically.
 */
final class TableNameNormalizer {

    private TableNameNormalizer() {
    }

    /** Strips quote characters, lower-cases (ASCII), and trims. */
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

    /** The bare table name: the segment after the last {@code .} of an already-normalized identifier. */
    static String suffix(String normalized) {
        int dot = normalized.lastIndexOf('.');
        return dot < 0 ? normalized : normalized.substring(dot + 1);
    }

    /**
     * True when two normalized identifiers refer to the same table. Exact match always wins; when
     * either side is unqualified (no schema), the bare table names are compared. Two
     * schema-qualified names in different schemas never match, avoiding cross-schema false positives.
     */
    static boolean matches(String referencedNormalized, String classificationNormalized) {
        if (referencedNormalized.isEmpty() || classificationNormalized.isEmpty()) {
            return false;
        }
        if (referencedNormalized.equals(classificationNormalized)) {
            return true;
        }
        boolean refBare = referencedNormalized.indexOf('.') < 0;
        boolean classBare = classificationNormalized.indexOf('.') < 0;
        if (refBare || classBare) {
            return suffix(referencedNormalized).equals(suffix(classificationNormalized));
        }
        return false;
    }
}
