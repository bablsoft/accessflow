package com.bablsoft.accessflow.engine.redis;

import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.SqlParseResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses and validates a submitted redis-cli command into an engine-neutral {@link SqlParseResult}
 * and an executable {@link ParsedRedisCommand}. The command is tokenized respecting single/double
 * quotes; only a single command is accepted (multi-line / multi-command input is rejected, the
 * key-value analogue of the SQL engine's multi-statement ban). The command name is matched against
 * the {@link RedisCommand} allow-list — server-side scripting and blast-radius commands are
 * rejected with a distinct {@code forbidden} message, anything else unknown with an
 * {@code unsupported} message, all HTTP 422.
 *
 * <p>{@code referencedTables} carries the key <em>prefix</em> (text before the first {@code :}) of
 * every key argument — {@code orders:*}→{@code orders}, {@code user:42}→{@code user}, bare
 * {@code foo}→{@code foo} — so schema allow-lists, permissions, and row-security policies target a
 * meaningful key namespace. Glob-only prefixes (e.g. {@code *}) contribute nothing (deny by
 * "no tables detected").
 */
class RedisCommandParser {

    private static final char[] GLOB_CHARS = {'*', '?', '['};

    private final EngineMessages messages;

    RedisCommandParser(EngineMessages messages) {
        this.messages = messages;
    }

    /** Engine-neutral parse result for the workflow layer (query type, key prefixes). */
    SqlParseResult parse(String query) {
        var parsed = parseCommand(query);
        return new SqlParseResult(parsed.command().queryType(), false, List.of(query),
                parsed.keyPrefixes(), false, false);
    }

    /** Full parse to the executable {@link ParsedRedisCommand}; reused by the executor. */
    ParsedRedisCommand parseCommand(String query) {
        if (query == null || query.isBlank()) {
            throw invalid("error.redis.blank");
        }
        try {
            var line = singleLine(query.strip());
            var tokens = tokenize(line);
            if (tokens.isEmpty()) {
                throw new RedisParseException("error.redis.empty_command");
            }
            var name = tokens.get(0);
            if (RedisCommand.isForbidden(name)) {
                throw new RedisParseException("error.redis.forbidden_command",
                        name.toUpperCase(Locale.ROOT));
            }
            var command = RedisCommand.find(name);
            if (command == null) {
                throw new RedisParseException("error.redis.unsupported_command",
                        name.toUpperCase(Locale.ROOT));
            }
            var args = tokens.subList(1, tokens.size());
            if (args.size() < command.minArgs()) {
                throw new RedisParseException("error.redis.argument_required",
                        command.name());
            }
            return new ParsedRedisCommand(command, args, keyPrefixes(command, args));
        } catch (RedisParseException ex) {
            throw invalid(ex.messageKey(), ex.args());
        }
    }

    /** Collapse trailing/leading blank lines; reject input carrying more than one command line. */
    private static String singleLine(String query) {
        var lines = new ArrayList<String>();
        for (var line : query.split("\\R")) {
            if (!line.isBlank()) {
                lines.add(line.strip());
            }
        }
        if (lines.size() > 1) {
            throw new RedisParseException("error.redis.multiple_commands");
        }
        return lines.isEmpty() ? "" : lines.get(0);
    }

    /** Split a command line into tokens, honouring single/double quotes (quotes stripped). */
    private static List<String> tokenize(String line) {
        var tokens = new ArrayList<String>();
        var current = new StringBuilder();
        boolean inToken = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
                continue;
            }
            switch (c) {
                case '\'', '"' -> {
                    quote = c;
                    inToken = true;
                }
                case ' ', '\t' -> {
                    if (inToken) {
                        tokens.add(current.toString());
                        current.setLength(0);
                        inToken = false;
                    }
                }
                default -> {
                    current.append(c);
                    inToken = true;
                }
            }
        }
        if (quote != 0) {
            throw new RedisParseException("error.redis.unbalanced");
        }
        if (inToken) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static Set<String> keyPrefixes(RedisCommand command, List<String> args) {
        var prefixes = new LinkedHashSet<String>();
        switch (command.keyArity()) {
            case NONE -> { /* no key */ }
            case FIRST -> addPrefix(prefixes, args.get(0));
            case FIRST_TWO -> {
                addPrefix(prefixes, args.get(0));
                addPrefix(prefixes, args.get(1));
            }
            case ALL -> args.forEach(arg -> addPrefix(prefixes, arg));
            case ALTERNATING -> {
                for (int i = 0; i < args.size(); i += 2) {
                    addPrefix(prefixes, args.get(i));
                }
            }
            case PATTERN_FIRST -> addPrefix(prefixes, args.get(0));
            case SCAN_MATCH -> {
                for (int i = 1; i < args.size() - 1; i++) {
                    if (args.get(i).equalsIgnoreCase("MATCH")) {
                        addPrefix(prefixes, args.get(i + 1));
                        break;
                    }
                }
            }
        }
        return prefixes;
    }

    /** Lowercased text before the first {@code :}; skipped if it still carries a glob metacharacter. */
    private static void addPrefix(Set<String> prefixes, String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        int colon = key.indexOf(':');
        var prefix = (colon >= 0 ? key.substring(0, colon) : key).toLowerCase(Locale.ROOT);
        if (prefix.isEmpty() || containsGlob(prefix)) {
            return;
        }
        prefixes.add(prefix);
    }

    private static boolean containsGlob(String s) {
        for (char g : GLOB_CHARS) {
            if (s.indexOf(g) >= 0) {
                return true;
            }
        }
        return false;
    }

    private InvalidSqlException invalid(String key, Object... args) {
        return new InvalidSqlException(messages.get(key, args));
    }
}
