package com.bablsoft.accessflow.engine.redis;

/**
 * Describes where a {@link RedisCommand}'s key argument(s) sit so the parser can derive the
 * {@code referencedTables} key prefixes (text before the first {@code :}). Keys are taken from the
 * argument list (the tokens after the command name).
 */
enum KeyArity {
    /** No key argument: {@code DBSIZE}, {@code RANDOMKEY}, {@code FLUSHDB}, {@code SWAPDB}. */
    NONE,
    /** The single key at argument index 0 (the common case: {@code GET key}, {@code HSET key …}). */
    FIRST,
    /** Two keys at argument indexes 0 and 1: {@code COPY src dst}, {@code RENAME}, {@code SMOVE}. */
    FIRST_TWO,
    /** Every argument is a key: {@code MGET}, {@code DEL}, {@code UNLINK}, {@code EXISTS}. */
    ALL,
    /** Alternating key/value pairs — keys at 0, 2, 4, …: {@code MSET}, {@code MSETNX}. */
    ALTERNATING,
    /** {@code KEYS pattern}: the glob pattern is at argument index 0. */
    PATTERN_FIRST,
    /** {@code SCAN cursor [MATCH pattern] …}: the prefix comes from the optional {@code MATCH} pattern. */
    SCAN_MATCH
}
