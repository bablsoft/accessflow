package com.bablsoft.accessflow.schemachange.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * {@code statements_checksum} (#879): SHA-256 hex over the ordered, normalised statement text
 * joined by a newline. Normalisation is trim plus one trailing {@code ;} removed, and the
 * normalised form is what the authoring service stores, so the promotion service (#880) recomputes
 * the same value from the rows it copies. Reordering two statements changes the value.
 */
final class SchemaChangeChecksum {

    private SchemaChangeChecksum() {
    }

    static String normalize(String sql) {
        var trimmed = sql == null ? "" : sql.strip();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).strip();
        }
        return trimmed;
    }

    /** Null for an empty list — a set without statements has nothing to attest. */
    static String of(List<String> normalizedStatements) {
        if (normalizedStatements == null || normalizedStatements.isEmpty()) {
            return null;
        }
        var joined = String.join("\n", normalizedStatements);
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is mandatory in every JDK", ex);
        }
    }
}
