package com.bablsoft.accessflow.engine.redis;

import java.util.List;
import java.util.Set;

/**
 * A parsed, validated Redis command: the allow-listed {@link RedisCommand}, the argument tokens
 * (everything after the command name, quotes already stripped), and the lowercased key-prefix set
 * (text before the first {@code :} of each key argument) carried as {@code referencedTables}.
 */
record ParsedRedisCommand(RedisCommand command, List<String> args, Set<String> keyPrefixes) {

    ParsedRedisCommand {
        args = List.copyOf(args);
        keyPrefixes = Set.copyOf(keyPrefixes);
    }

    /** The first argument (the key for the common single-key commands). */
    String key() {
        return args.get(0);
    }

    String arg(int index) {
        return args.get(index);
    }

    int argCount() {
        return args.size();
    }
}
