package com.bablsoft.accessflow.engine.redis;

import com.bablsoft.accessflow.core.api.QueryType;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The Redis command allow-list (issue AF-419). Each constant maps a redis-cli command to the
 * host's {@link QueryType} (so permissions / routing policies / approval plans apply unchanged), a
 * {@link ResultKind} (how the reply is materialized), a {@link KeyArity} (where its key
 * argument(s) sit, for {@code referencedTables} prefix extraction), and the minimum number of
 * arguments the command requires (validated at parse time so execution is index-safe). The enum
 * constant name is the uppercase Redis command token.
 *
 * <p>This is a strict allow-list: a command not present here is rejected at parse time
 * ({@code error.redis.unsupported_command}, HTTP 422). A separate {@link #FORBIDDEN} set names the
 * blast-radius / server-side-scripting / blocking / multi-command operations that are rejected with
 * a distinct {@code error.redis.forbidden_command} message — the key-value analogue of the SQL
 * engine's {@code $where} ban.
 */
enum RedisCommand {
    // ---- reads → SELECT ---------------------------------------------------------------------
    GET(QueryType.SELECT, ResultKind.STRING, KeyArity.FIRST, 1),
    MGET(QueryType.SELECT, ResultKind.MGET, KeyArity.ALL, 1),
    STRLEN(QueryType.SELECT, ResultKind.SCALAR, KeyArity.FIRST, 1),
    GETRANGE(QueryType.SELECT, ResultKind.VALUE_RETURNING, KeyArity.FIRST, 3),
    EXISTS(QueryType.SELECT, ResultKind.SCALAR, KeyArity.ALL, 1),
    TYPE(QueryType.SELECT, ResultKind.SCALAR, KeyArity.FIRST, 1),
    TTL(QueryType.SELECT, ResultKind.SCALAR, KeyArity.FIRST, 1),
    PTTL(QueryType.SELECT, ResultKind.SCALAR, KeyArity.FIRST, 1),
    KEYS(QueryType.SELECT, ResultKind.KEYS_LIST, KeyArity.PATTERN_FIRST, 1),
    SCAN(QueryType.SELECT, ResultKind.KEYS_LIST, KeyArity.SCAN_MATCH, 1),
    DBSIZE(QueryType.SELECT, ResultKind.SCALAR, KeyArity.NONE, 0),
    GETBIT(QueryType.SELECT, ResultKind.SCALAR, KeyArity.FIRST, 2),
    BITCOUNT(QueryType.SELECT, ResultKind.SCALAR, KeyArity.FIRST, 1),
    HGET(QueryType.SELECT, ResultKind.HASH_FIELDS, KeyArity.FIRST, 2),
    HMGET(QueryType.SELECT, ResultKind.HASH_FIELDS, KeyArity.FIRST, 2),
    HGETALL(QueryType.SELECT, ResultKind.HASH_MAP, KeyArity.FIRST, 1),
    HKEYS(QueryType.SELECT, ResultKind.COLLECTION, KeyArity.FIRST, 1),
    HVALS(QueryType.SELECT, ResultKind.COLLECTION, KeyArity.FIRST, 1),
    HLEN(QueryType.SELECT, ResultKind.SCALAR, KeyArity.FIRST, 1),
    HEXISTS(QueryType.SELECT, ResultKind.SCALAR, KeyArity.FIRST, 2),
    LRANGE(QueryType.SELECT, ResultKind.COLLECTION, KeyArity.FIRST, 3),
    LINDEX(QueryType.SELECT, ResultKind.VALUE_RETURNING, KeyArity.FIRST, 2),
    LLEN(QueryType.SELECT, ResultKind.SCALAR, KeyArity.FIRST, 1),
    SMEMBERS(QueryType.SELECT, ResultKind.COLLECTION, KeyArity.FIRST, 1),
    SISMEMBER(QueryType.SELECT, ResultKind.SCALAR, KeyArity.FIRST, 2),
    SCARD(QueryType.SELECT, ResultKind.SCALAR, KeyArity.FIRST, 1),
    ZRANGE(QueryType.SELECT, ResultKind.COLLECTION, KeyArity.FIRST, 3),
    ZSCORE(QueryType.SELECT, ResultKind.SCALAR, KeyArity.FIRST, 2),
    ZCARD(QueryType.SELECT, ResultKind.SCALAR, KeyArity.FIRST, 1),
    ZRANK(QueryType.SELECT, ResultKind.SCALAR, KeyArity.FIRST, 2),

    // ---- conditional create → INSERT --------------------------------------------------------
    SETNX(QueryType.INSERT, ResultKind.COUNT_WRITE, KeyArity.FIRST, 2),
    MSETNX(QueryType.INSERT, ResultKind.COUNT_WRITE, KeyArity.ALTERNATING, 2),
    HSETNX(QueryType.INSERT, ResultKind.COUNT_WRITE, KeyArity.FIRST, 3),
    RENAMENX(QueryType.INSERT, ResultKind.COUNT_WRITE, KeyArity.FIRST_TWO, 2),

    // ---- modify → UPDATE --------------------------------------------------------------------
    SET(QueryType.UPDATE, ResultKind.STATUS_WRITE, KeyArity.FIRST, 2),
    SETEX(QueryType.UPDATE, ResultKind.STATUS_WRITE, KeyArity.FIRST, 3),
    PSETEX(QueryType.UPDATE, ResultKind.STATUS_WRITE, KeyArity.FIRST, 3),
    MSET(QueryType.UPDATE, ResultKind.STATUS_WRITE, KeyArity.ALTERNATING, 2),
    APPEND(QueryType.UPDATE, ResultKind.VALUE_RETURNING, KeyArity.FIRST, 2),
    SETRANGE(QueryType.UPDATE, ResultKind.VALUE_RETURNING, KeyArity.FIRST, 3),
    GETSET(QueryType.UPDATE, ResultKind.VALUE_RETURNING, KeyArity.FIRST, 2),
    INCR(QueryType.UPDATE, ResultKind.VALUE_RETURNING, KeyArity.FIRST, 1),
    DECR(QueryType.UPDATE, ResultKind.VALUE_RETURNING, KeyArity.FIRST, 1),
    INCRBY(QueryType.UPDATE, ResultKind.VALUE_RETURNING, KeyArity.FIRST, 2),
    DECRBY(QueryType.UPDATE, ResultKind.VALUE_RETURNING, KeyArity.FIRST, 2),
    INCRBYFLOAT(QueryType.UPDATE, ResultKind.VALUE_RETURNING, KeyArity.FIRST, 2),
    SETBIT(QueryType.UPDATE, ResultKind.VALUE_RETURNING, KeyArity.FIRST, 3),
    HSET(QueryType.UPDATE, ResultKind.COUNT_WRITE, KeyArity.FIRST, 3),
    HMSET(QueryType.UPDATE, ResultKind.STATUS_WRITE, KeyArity.FIRST, 3),
    HINCRBY(QueryType.UPDATE, ResultKind.VALUE_RETURNING, KeyArity.FIRST, 3),
    HINCRBYFLOAT(QueryType.UPDATE, ResultKind.VALUE_RETURNING, KeyArity.FIRST, 3),
    LPUSH(QueryType.UPDATE, ResultKind.VALUE_RETURNING, KeyArity.FIRST, 2),
    RPUSH(QueryType.UPDATE, ResultKind.VALUE_RETURNING, KeyArity.FIRST, 2),
    LSET(QueryType.UPDATE, ResultKind.STATUS_WRITE, KeyArity.FIRST, 3),
    SADD(QueryType.UPDATE, ResultKind.COUNT_WRITE, KeyArity.FIRST, 2),
    ZADD(QueryType.UPDATE, ResultKind.COUNT_WRITE, KeyArity.FIRST, 3),
    ZINCRBY(QueryType.UPDATE, ResultKind.VALUE_RETURNING, KeyArity.FIRST, 3),
    EXPIRE(QueryType.UPDATE, ResultKind.COUNT_WRITE, KeyArity.FIRST, 2),
    PEXPIRE(QueryType.UPDATE, ResultKind.COUNT_WRITE, KeyArity.FIRST, 2),
    EXPIREAT(QueryType.UPDATE, ResultKind.COUNT_WRITE, KeyArity.FIRST, 2),
    PEXPIREAT(QueryType.UPDATE, ResultKind.COUNT_WRITE, KeyArity.FIRST, 2),
    PERSIST(QueryType.UPDATE, ResultKind.COUNT_WRITE, KeyArity.FIRST, 1),
    RENAME(QueryType.UPDATE, ResultKind.STATUS_WRITE, KeyArity.FIRST_TWO, 2),
    COPY(QueryType.UPDATE, ResultKind.COUNT_WRITE, KeyArity.FIRST_TWO, 2),
    SMOVE(QueryType.UPDATE, ResultKind.COUNT_WRITE, KeyArity.FIRST_TWO, 3),

    // ---- remove → DELETE --------------------------------------------------------------------
    DEL(QueryType.DELETE, ResultKind.COUNT_WRITE, KeyArity.ALL, 1),
    UNLINK(QueryType.DELETE, ResultKind.COUNT_WRITE, KeyArity.ALL, 1),
    GETDEL(QueryType.DELETE, ResultKind.VALUE_RETURNING, KeyArity.FIRST, 1),
    HDEL(QueryType.DELETE, ResultKind.COUNT_WRITE, KeyArity.FIRST, 2),
    SREM(QueryType.DELETE, ResultKind.COUNT_WRITE, KeyArity.FIRST, 2),
    ZREM(QueryType.DELETE, ResultKind.COUNT_WRITE, KeyArity.FIRST, 2),
    LREM(QueryType.DELETE, ResultKind.COUNT_WRITE, KeyArity.FIRST, 3),
    LPOP(QueryType.DELETE, ResultKind.VALUE_RETURNING, KeyArity.FIRST, 1),
    RPOP(QueryType.DELETE, ResultKind.VALUE_RETURNING, KeyArity.FIRST, 1),
    SPOP(QueryType.DELETE, ResultKind.VALUE_RETURNING, KeyArity.FIRST, 1),

    // ---- admin → DDL (governed via the approval workflow) -----------------------------------
    FLUSHDB(QueryType.DDL, ResultKind.DDL, KeyArity.NONE, 0);

    /**
     * Explicitly forbidden commands — rejected up front with {@code error.redis.forbidden_command}
     * (HTTP 422). Server-side scripting and blast-radius operations (the issue's mandated ban),
     * plus the obvious siblings that don't fit a governed request/response model: blocking reads,
     * pub/sub, multi-command transactions, replication/cluster/admin, persistence, and connection
     * state mutation. Anything outside both this set and the allow-list above is reported as
     * {@code error.redis.unsupported_command}.
     */
    static final Set<String> FORBIDDEN = Stream.of(
            // server-side scripting / functions
            "EVAL", "EVAL_RO", "EVALSHA", "EVALSHA_RO", "FCALL", "FCALL_RO", "SCRIPT", "FUNCTION",
            // blast radius / admin
            "CONFIG", "FLUSHALL", "SHUTDOWN", "DEBUG", "MIGRATE", "RESET", "FAILOVER",
            "SLOWLOG", "LATENCY", "MEMORY", "MODULE", "CLIENT", "ACL", "CLUSTER",
            // replication / persistence / connection state (SWAPDB is unscoped + not exposed on a pooled client)
            "SLAVEOF", "REPLICAOF", "SAVE", "BGSAVE", "BGREWRITEAOF", "SELECT", "MOVE", "SWAPDB",
            // transactions / multi-command
            "MULTI", "EXEC", "DISCARD", "WATCH", "UNWATCH",
            // blocking
            "BLPOP", "BRPOP", "BLMOVE", "BRPOPLPUSH", "BLMPOP", "BZPOPMIN", "BZPOPMAX", "BZMPOP",
            "WAIT", "WAITAOF",
            // pub/sub
            "SUBSCRIBE", "PSUBSCRIBE", "SSUBSCRIBE", "UNSUBSCRIBE", "PUNSUBSCRIBE",
            "PUBLISH", "SPUBLISH")
            .collect(Collectors.toUnmodifiableSet());

    private static final Map<String, RedisCommand> BY_NAME = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(Enum::name, c -> c));

    private final QueryType queryType;
    private final ResultKind resultKind;
    private final KeyArity keyArity;
    private final int minArgs;

    RedisCommand(QueryType queryType, ResultKind resultKind, KeyArity keyArity, int minArgs) {
        this.queryType = queryType;
        this.resultKind = resultKind;
        this.keyArity = keyArity;
        this.minArgs = minArgs;
    }

    QueryType queryType() {
        return queryType;
    }

    ResultKind resultKind() {
        return resultKind;
    }

    KeyArity keyArity() {
        return keyArity;
    }

    int minArgs() {
        return minArgs;
    }

    /** The allow-listed command for the given token (case-insensitive), or {@code null} if unknown. */
    static RedisCommand find(String token) {
        return token == null ? null : BY_NAME.get(token.toUpperCase(Locale.ROOT));
    }

    static boolean isForbidden(String token) {
        return token != null && FORBIDDEN.contains(token.toUpperCase(Locale.ROOT));
    }
}
