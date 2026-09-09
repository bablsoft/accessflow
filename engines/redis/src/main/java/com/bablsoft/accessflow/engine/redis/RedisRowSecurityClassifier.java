package com.bablsoft.accessflow.engine.redis;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.RowSecurityClassification;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Redis's whole row-security model, in one place: a row predicate has no meaning over a key-value
 * store, so any directive targeting a key prefix the command touches makes the command
 * <em>unrewritable</em> — Redis fails closed rather than serving unfiltered values.
 *
 * <p>Both the enforcement path ({@link RedisQueryExecutor}) and the offline classification path
 * ({@link RedisQueryEngine#classifyRowSecurity}) go through this class, so the policy simulator can
 * never predict something different from what execution would actually do.
 */
class RedisRowSecurityClassifier {

    private final EngineMessages messages;

    RedisRowSecurityClassifier(EngineMessages messages) {
        this.messages = messages;
    }

    /** Throws when any directive targets a prefix the parsed command references. */
    void failClosedOnRowSecurity(ParsedRedisCommand parsed, List<RowSecurityDirective> directives) {
        var prefix = blockingPrefix(parsed.keyPrefixes(), directives);
        if (prefix != null) {
            throw unrewritable(prefix);
        }
    }

    /** Throws when any directive targets this single prefix (the table-sampling path). */
    void failClosedForPrefix(String prefix, List<RowSecurityDirective> directives) {
        var blocking = blockingPrefix(Set.of(prefix.toLowerCase(Locale.ROOT).trim()), directives);
        if (blocking != null) {
            throw unrewritable(prefix);
        }
    }

    /**
     * Classify offline what row security would do to a parsed command (issue AF-630). Redis has
     * exactly two answers — it can never filter, so a directive that applies always denies the
     * command outright, and one that does not apply leaves it untouched.
     */
    RowSecurityClassification classify(String engineId, ParsedRedisCommand parsed,
                                       List<RowSecurityDirective> directives) {
        var prefix = blockingPrefix(parsed.keyPrefixes(), directives);
        return prefix == null
                ? RowSecurityClassification.notApplicable(engineId)
                : RowSecurityClassification.failClosed(engineId, message(prefix));
    }

    /** The first referenced prefix any directive targets, or {@code null} when none does. */
    private static String blockingPrefix(Set<String> prefixes, List<RowSecurityDirective> directives) {
        if (directives == null || directives.isEmpty()) {
            return null;
        }
        for (var directive : directives) {
            var prefix = matchingPrefix(directive.tableRef(), prefixes);
            if (prefix != null) {
                return prefix;
            }
        }
        return null;
    }

    /** The referenced prefix a directive's tableRef targets (last dot-segment, lowercased), or null. */
    private static String matchingPrefix(String tableRef, Set<String> prefixes) {
        if (tableRef == null) {
            return null;
        }
        var ref = tableRef.toLowerCase(Locale.ROOT).trim();
        int dot = ref.lastIndexOf('.');
        var candidate = dot >= 0 ? ref.substring(dot + 1) : ref;
        return prefixes.contains(candidate) ? candidate : null;
    }

    private UnrewritableRowSecurityException unrewritable(String prefix) {
        return new UnrewritableRowSecurityException(message(prefix));
    }

    private String message(String prefix) {
        return messages.get("error.row_security_redis_unsupported", prefix);
    }
}
