package com.bablsoft.accessflow.engine.redis;

/**
 * How a {@link RedisCommand}'s reply is materialized into an engine-neutral result. Decoupled from
 * {@link com.bablsoft.accessflow.core.api.QueryType} (which drives permissions / approval) so a
 * value-returning mutator (e.g. {@code GETDEL}, {@code LPOP}, {@code INCR}) is classified as a
 * DELETE/UPDATE for governance yet still surfaces its returned value as a one-row result set.
 */
enum ResultKind {
    /** {@code SCAN}/{@code KEYS}: a {@code key} column, one row per matched key. */
    KEYS_LIST,
    /** {@code GET}: a single {@code value} column, one row. */
    STRING,
    /** {@code MGET}: {@code key},{@code value} columns, one row per requested key. */
    MGET,
    /** {@code HGETALL}: hash field names as columns, a single row of values. */
    HASH_MAP,
    /** {@code HGET}/{@code HMGET}: the requested field name(s) as columns, one row. */
    HASH_FIELDS,
    /** {@code LRANGE}/{@code SMEMBERS}/{@code HKEYS}/{@code HVALS}/{@code ZRANGE}: a payload column, N rows. */
    COLLECTION,
    /** {@code ZRANGE … WITHSCORES}: {@code member},{@code score} columns, N rows. */
    ZSET_WITHSCORES,
    /** {@code TTL}/{@code LLEN}/{@code EXISTS}/{@code STRLEN}/…: a single scalar {@code value}, one row. */
    SCALAR,
    /** A value-returning mutator ({@code GETSET}/{@code GETDEL}/{@code LPOP}/{@code INCR}/…): {@code value} column, one row. */
    VALUE_RETURNING,
    /** A write whose reply is a numeric count ({@code DEL}/{@code SADD}/{@code EXPIRE}/…): {@code UpdateExecutionResult}. */
    COUNT_WRITE,
    /** A write whose reply is a status ({@code SET}/{@code MSET}/{@code RENAME}/…): {@code UpdateExecutionResult(1)}. */
    STATUS_WRITE,
    /** An admin command ({@code FLUSHDB}/{@code SWAPDB}): {@code UpdateExecutionResult(0)}. */
    DDL
}
