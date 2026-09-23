package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.SchemaFingerprintService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Canonicalises a {@link DatabaseSchemaView} and hashes it with SHA-256 (#881).
 *
 * <p>The canonical form is
 * {@code schema{table(name:type:nullable:pk,…)[fromColumn>toTable.toColumn,…]…}…}, with schemas,
 * tables, columns and foreign keys each sorted by their own rendered token so two introspections of
 * the same schema in a different order fingerprint identically.
 *
 * <p>Every rendered name is escaped. Without it a column literally named {@code a:b} of type
 * {@code c} and a column {@code a} of type {@code b:c} would produce the same token — the collision
 * the predecessor {@code SchemaHasher} carried.
 */
@Service
public class DefaultSchemaFingerprintService implements SchemaFingerprintService {

    /** Every character the grammar gives structural meaning to. */
    private static final String DELIMITERS = "{}()[]:,>.";

    @Override
    public String fingerprint(DatabaseSchemaView schema) {
        return sha256(canonicalize(schema));
    }

    private static String canonicalize(DatabaseSchemaView schema) {
        if (schema == null || schema.schemas() == null) {
            return "";
        }
        var sb = new StringBuilder();
        sorted(schema.schemas(), s -> key(s == null ? null : s.name()))
                .forEach(s -> appendSchema(sb, s));
        return sb.toString();
    }

    private static void appendSchema(StringBuilder sb, DatabaseSchemaView.Schema schema) {
        if (schema == null) {
            return;
        }
        sb.append(key(schema.name())).append('{');
        sorted(schema.tables(), t -> key(t == null ? null : t.name()))
                .forEach(t -> appendTable(sb, t));
        sb.append('}');
    }

    private static void appendTable(StringBuilder sb, DatabaseSchemaView.Table table) {
        if (table == null) {
            return;
        }
        sb.append(key(table.name())).append('(');
        sorted(table.columns(), DefaultSchemaFingerprintService::columnToken)
                .forEach(c -> sb.append(columnToken(c)).append(','));
        sb.append(")[");
        sorted(table.foreignKeys(), DefaultSchemaFingerprintService::foreignKeyToken)
                .forEach(fk -> sb.append(foreignKeyToken(fk)).append(','));
        sb.append(']');
    }

    private static String columnToken(DatabaseSchemaView.Column column) {
        if (column == null) {
            return "::0:0";
        }
        return key(column.name()) + ':' + key(column.type())
                + ':' + (column.nullable() ? '1' : '0')
                + ':' + (column.primaryKey() ? '1' : '0');
    }

    private static String foreignKeyToken(DatabaseSchemaView.ForeignKey foreignKey) {
        if (foreignKey == null) {
            return ">.";
        }
        return key(foreignKey.fromColumn()) + '>' + key(foreignKey.toTable())
                + '.' + key(foreignKey.toColumn());
    }

    private static <T> Stream<T> sorted(List<T> values, Function<T, String> token) {
        if (values == null) {
            return Stream.empty();
        }
        return values.stream().sorted(Comparator.comparing(token));
    }

    /** Lowercased (Locale.ROOT, so the fingerprint never depends on the JVM locale) and escaped. */
    private static String key(String value) {
        if (value == null) {
            return "";
        }
        var lower = value.toLowerCase(Locale.ROOT);
        var sb = new StringBuilder(lower.length());
        for (var i = 0; i < lower.length(); i++) {
            var c = lower.charAt(i);
            if (c == '\\' || DELIMITERS.indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String sha256(String canonical) {
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
}
