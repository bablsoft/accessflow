package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Locale;

/**
 * Computes a stable, order-independent SHA-256 fingerprint of a {@link DatabaseSchemaView} (AF-449).
 * Used to record what a datasource's schema looked like when a query executed (forensic metadata) and to
 * surface source-vs-target drift in the replay audit row. Schemas, tables, and columns are sorted before
 * hashing so two introspections of the same schema in a different order hash identically.
 */
@Component
class SchemaHasher {

    /** Returns a 64-char lowercase hex SHA-256 of the canonical schema form. */
    String hash(DatabaseSchemaView schema) {
        var canonical = canonicalize(schema);
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            var hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private static String canonicalize(DatabaseSchemaView schema) {
        if (schema == null || schema.schemas() == null) {
            return "";
        }
        var sb = new StringBuilder();
        schema.schemas().stream()
                .sorted(Comparator.comparing(s -> lower(s.name())))
                .forEach(s -> {
                    sb.append(lower(s.name())).append('{');
                    if (s.tables() != null) {
                        s.tables().stream()
                                .sorted(Comparator.comparing(t -> lower(t.name())))
                                .forEach(t -> {
                                    sb.append(lower(t.name())).append('(');
                                    if (t.columns() != null) {
                                        t.columns().stream()
                                                .sorted(Comparator.comparing(c -> lower(c.name())))
                                                .forEach(c -> sb.append(lower(c.name())).append(':')
                                                        .append(lower(c.type())).append(','));
                                    }
                                    sb.append(')');
                                });
                    }
                    sb.append('}');
                });
        return sb.toString();
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
