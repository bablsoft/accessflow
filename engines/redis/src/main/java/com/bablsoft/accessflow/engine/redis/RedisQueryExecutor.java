package com.bablsoft.accessflow.engine.redis;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.EngineMessages;
import com.bablsoft.accessflow.core.api.QueryExecutionFailedException;
import com.bablsoft.accessflow.core.api.QueryExecutionRequest;
import com.bablsoft.accessflow.core.api.QueryExecutionResult;
import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.SampleTableRequest;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import com.bablsoft.accessflow.core.api.UpdateExecutionResult;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.Tuple;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Executes a Redis command for a {@code REDIS} datasource — the key-value-engine analogue of the
 * host's JDBC execution path. Re-parses the command, fails closed if any row-security policy
 * targets a referenced key prefix (row security has no meaning in a key-value model), then runs the
 * command through the native Jedis client and materializes the reply via {@link RedisResultMapper}
 * (which applies field masking). Read-shaped replies return a {@code SelectExecutionResult};
 * count/status/admin writes return an {@code UpdateExecutionResult}. Value-returning mutators
 * ({@code GETDEL}, {@code LPOP}, {@code INCR}, …) classify as DELETE/UPDATE for governance yet
 * surface their returned value as a one-row result set.
 */
class RedisQueryExecutor {

    private final RedisClientManager clientManager;
    private final RedisCommandParser parser;
    private final RedisResultMapper resultMapper;
    private final RedisExceptionTranslator exceptionTranslator;
    private final EngineMessages messages;
    private final Clock clock;

    RedisQueryExecutor(RedisClientManager clientManager, RedisCommandParser parser,
                       RedisResultMapper resultMapper, RedisExceptionTranslator exceptionTranslator,
                       EngineMessages messages, Clock clock) {
        this.clientManager = clientManager;
        this.parser = parser;
        this.resultMapper = resultMapper;
        this.exceptionTranslator = exceptionTranslator;
        this.messages = messages;
        this.clock = clock;
    }

    QueryExecutionResult execute(QueryExecutionRequest request,
                                 DatasourceConnectionDescriptor descriptor, int maxRows,
                                 Duration timeout) {
        var start = clock.instant();
        var parsed = parser.parseCommand(request.sql());
        failClosedOnRowSecurity(parsed, request.rowSecurityPredicates());
        var jedis = clientManager.client(descriptor);
        var restricted = request.restrictedColumns();
        var masks = request.columnMasks();
        try {
            return dispatch(jedis, parsed, maxRows, restricted, masks, start);
        } catch (JedisException ex) {
            throw exceptionTranslator.translate(ex, timeout);
        } catch (NumberFormatException ex) {
            throw new QueryExecutionFailedException(messages.get("error.query_execution_failed"),
                    ex.getMessage(), null, 0, ex);
        }
    }

    private QueryExecutionResult dispatch(JedisPooled jedis, ParsedRedisCommand p, int maxRows,
                                          List<String> restricted, List<?> masksRaw, Instant start) {
        @SuppressWarnings("unchecked")
        var masks = (List<com.bablsoft.accessflow.core.api.ColumnMaskDirective>) masksRaw;
        var key = p.command().keyArity() == KeyArity.NONE ? null : p.arg(0);
        return switch (p.command()) {
            // ---- reads --------------------------------------------------------------------
            case GET -> select(resultMapper.singleValue(jedis.get(key), dur(start), restricted, masks));
            case MGET -> select(resultMapper.keyValues(p.args(),
                    jedis.mget(p.args().toArray(new String[0])), dur(start), restricted, masks));
            case STRLEN -> scalar(jedis.strlen(key), start, restricted, masks);
            case GETRANGE -> select(resultMapper.singleValue(
                    jedis.getrange(key, l(p.arg(1)), l(p.arg(2))), dur(start), restricted, masks));
            case EXISTS -> scalar(jedis.exists(p.args().toArray(new String[0])), start, restricted, masks);
            case TYPE -> scalar(jedis.type(key), start, restricted, masks);
            case TTL -> scalar(jedis.ttl(key), start, restricted, masks);
            case PTTL -> scalar(jedis.pttl(key), start, restricted, masks);
            case DBSIZE -> scalar(jedis.dbSize(), start, restricted, masks);
            case GETBIT -> scalar(jedis.getbit(key, l(p.arg(1))), start, restricted, masks);
            case BITCOUNT -> scalar(p.argCount() >= 3 ? jedis.bitcount(key, l(p.arg(1)), l(p.arg(2)))
                    : jedis.bitcount(key), start, restricted, masks);
            case KEYS -> select(resultMapper.keys(new ArrayList<>(jedis.keys(key)), false, maxRows,
                    dur(start), restricted, masks));
            case SCAN -> scan(jedis, p, maxRows, restricted, masks, start);
            case HGET -> select(resultMapper.hashFields(List.of(p.arg(1)),
                    nullableList(jedis.hget(key, p.arg(1))), dur(start), restricted, masks));
            case HMGET -> select(resultMapper.hashFields(p.args().subList(1, p.argCount()),
                    jedis.hmget(key, p.args().subList(1, p.argCount()).toArray(new String[0])),
                    dur(start), restricted, masks));
            case HGETALL -> select(resultMapper.hashMap(jedis.hgetAll(key), dur(start), restricted, masks));
            case HKEYS -> select(resultMapper.collection(new ArrayList<>(jedis.hkeys(key)), maxRows,
                    dur(start), restricted, masks));
            case HVALS -> select(resultMapper.collection(jedis.hvals(key), maxRows, dur(start),
                    restricted, masks));
            case HLEN -> scalar(jedis.hlen(key), start, restricted, masks);
            case HEXISTS -> scalar(jedis.hexists(key, p.arg(1)), start, restricted, masks);
            case LRANGE -> select(resultMapper.collection(jedis.lrange(key, l(p.arg(1)), l(p.arg(2))),
                    maxRows, dur(start), restricted, masks));
            case LINDEX -> select(resultMapper.singleValue(jedis.lindex(key, l(p.arg(1))), dur(start),
                    restricted, masks));
            case LLEN -> scalar(jedis.llen(key), start, restricted, masks);
            case SMEMBERS -> select(resultMapper.collection(new ArrayList<>(jedis.smembers(key)),
                    maxRows, dur(start), restricted, masks));
            case SISMEMBER -> scalar(jedis.sismember(key, p.arg(1)), start, restricted, masks);
            case SCARD -> scalar(jedis.scard(key), start, restricted, masks);
            case ZRANGE -> zrange(jedis, p, key, maxRows, restricted, masks, start);
            case ZSCORE -> scalar(jedis.zscore(key, p.arg(1)), start, restricted, masks);
            case ZCARD -> scalar(jedis.zcard(key), start, restricted, masks);
            case ZRANK -> scalar(jedis.zrank(key, p.arg(1)), start, restricted, masks);

            // ---- conditional create (INSERT) ----------------------------------------------
            case SETNX -> count(jedis.setnx(key, p.arg(1)), start);
            case MSETNX -> count(jedis.msetnx(p.args().toArray(new String[0])), start);
            case HSETNX -> count(jedis.hsetnx(key, p.arg(1), p.arg(2)), start);
            case RENAMENX -> count(jedis.renamenx(key, p.arg(1)), start);

            // ---- modify (UPDATE) ----------------------------------------------------------
            case SET -> status(jedis.set(key, p.arg(1)), start);
            case SETEX -> status(jedis.setex(key, l(p.arg(1)), p.arg(2)), start);
            case PSETEX -> status(jedis.psetex(key, l(p.arg(1)), p.arg(2)), start);
            case MSET -> status(jedis.mset(p.args().toArray(new String[0])), start);
            case APPEND -> select(resultMapper.singleValue(jedis.append(key, p.arg(1)), dur(start),
                    restricted, masks));
            case SETRANGE -> select(resultMapper.singleValue(jedis.setrange(key, l(p.arg(1)), p.arg(2)),
                    dur(start), restricted, masks));
            case GETSET -> select(resultMapper.singleValue(jedis.getSet(key, p.arg(1)), dur(start),
                    restricted, masks));
            case INCR -> select(resultMapper.singleValue(jedis.incr(key), dur(start), restricted, masks));
            case DECR -> select(resultMapper.singleValue(jedis.decr(key), dur(start), restricted, masks));
            case INCRBY -> select(resultMapper.singleValue(jedis.incrBy(key, l(p.arg(1))), dur(start),
                    restricted, masks));
            case DECRBY -> select(resultMapper.singleValue(jedis.decrBy(key, l(p.arg(1))), dur(start),
                    restricted, masks));
            case INCRBYFLOAT -> select(resultMapper.singleValue(jedis.incrByFloat(key, d(p.arg(1))),
                    dur(start), restricted, masks));
            case SETBIT -> select(resultMapper.singleValue(jedis.setbit(key, l(p.arg(1)),
                    "1".equals(p.arg(2))), dur(start), restricted, masks));
            case HSET -> count(hset(jedis, p, key), start);
            case HMSET -> status(jedis.hmset(key, pairMap(p, 1)), start);
            case HINCRBY -> select(resultMapper.singleValue(jedis.hincrBy(key, p.arg(1), l(p.arg(2))),
                    dur(start), restricted, masks));
            case HINCRBYFLOAT -> select(resultMapper.singleValue(
                    jedis.hincrByFloat(key, p.arg(1), d(p.arg(2))), dur(start), restricted, masks));
            case LPUSH -> select(resultMapper.singleValue(
                    jedis.lpush(key, values(p, 1)), dur(start), restricted, masks));
            case RPUSH -> select(resultMapper.singleValue(
                    jedis.rpush(key, values(p, 1)), dur(start), restricted, masks));
            case LSET -> status(jedis.lset(key, l(p.arg(1)), p.arg(2)), start);
            case SADD -> count(jedis.sadd(key, values(p, 1)), start);
            case ZADD -> count(zadd(jedis, p, key), start);
            case ZINCRBY -> select(resultMapper.singleValue(jedis.zincrby(key, d(p.arg(1)), p.arg(2)),
                    dur(start), restricted, masks));
            case EXPIRE -> count(jedis.expire(key, l(p.arg(1))), start);
            case PEXPIRE -> count(jedis.pexpire(key, l(p.arg(1))), start);
            case EXPIREAT -> count(jedis.expireAt(key, l(p.arg(1))), start);
            case PEXPIREAT -> count(jedis.pexpireAt(key, l(p.arg(1))), start);
            case PERSIST -> count(jedis.persist(key), start);
            case RENAME -> status(jedis.rename(key, p.arg(1)), start);
            case COPY -> count(jedis.copy(key, p.arg(1), false) ? 1 : 0, start);
            case SMOVE -> count(jedis.smove(key, p.arg(1), p.arg(2)), start);

            // ---- remove (DELETE) ----------------------------------------------------------
            case DEL -> count(jedis.del(p.args().toArray(new String[0])), start);
            case UNLINK -> count(jedis.unlink(p.args().toArray(new String[0])), start);
            case GETDEL -> select(resultMapper.singleValue(jedis.getDel(key), dur(start), restricted, masks));
            case HDEL -> count(jedis.hdel(key, p.args().subList(1, p.argCount()).toArray(new String[0])), start);
            case SREM -> count(jedis.srem(key, p.args().subList(1, p.argCount()).toArray(new String[0])), start);
            case ZREM -> count(jedis.zrem(key, p.args().subList(1, p.argCount()).toArray(new String[0])), start);
            case LREM -> count(jedis.lrem(key, l(p.arg(1)), p.arg(2)), start);
            case LPOP -> pop(p.argCount() >= 2 ? jedis.lpop(key, (int) l(p.arg(1))) : null,
                    p.argCount() >= 2 ? null : jedis.lpop(key), maxRows, restricted, masks, start);
            case RPOP -> pop(p.argCount() >= 2 ? jedis.rpop(key, (int) l(p.arg(1))) : null,
                    p.argCount() >= 2 ? null : jedis.rpop(key), maxRows, restricted, masks, start);
            case SPOP -> pop(p.argCount() >= 2 ? new ArrayList<>(jedis.spop(key, l(p.arg(1)))) : null,
                    p.argCount() >= 2 ? null : jedis.spop(key), maxRows, restricted, masks, start);

            // ---- admin (DDL) --------------------------------------------------------------
            case FLUSHDB -> { jedis.flushDB(); yield new UpdateExecutionResult(0, dur(start)); }
        };
    }

    // ---- sample-data path (AF-443) --------------------------------------------------------------

    SelectExecutionResult sampleTable(SampleTableRequest request,
                                      DatasourceConnectionDescriptor descriptor, int maxRows,
                                      Duration timeout) {
        // Row security has no per-row meaning in a key-value model: fail closed if a policy targets
        // this prefix (parity with execute's failClosedOnRowSecurity). Otherwise SCAN the prefix and
        // fetch values, with field masking applied by the shared mapper.
        failClosedForPrefix(request.table(), request.rowSecurityPredicates());
        var jedis = clientManager.client(descriptor);
        var restricted = request.restrictedColumns();
        var masks = request.columnMasks();
        var start = clock.instant();
        try {
            var scanned = scanKeys(jedis, request.table(), maxRows + 1);
            boolean truncated = scanned.size() > maxRows;
            var keys = truncated ? scanned.subList(0, maxRows) : scanned;
            if (keys.isEmpty()) {
                return resultMapper.rows(List.of(RedisResultMapper.KEY_COLUMN), List.of(), false,
                        dur(start), restricted, masks);
            }
            if ("hash".equals(jedis.type(keys.get(0)))) {
                return sampleHashes(jedis, keys, truncated, dur(start), restricted, masks);
            }
            return sampleKeyValues(jedis, keys, truncated, dur(start), restricted, masks);
        } catch (JedisException ex) {
            throw exceptionTranslator.translate(ex, timeout);
        }
    }

    private SelectExecutionResult sampleKeyValues(JedisPooled jedis, List<String> keys,
                                                  boolean truncated, Duration duration,
                                                  List<String> restricted,
                                                  List<ColumnMaskDirective> masks) {
        var rows = new ArrayList<List<Object>>(keys.size());
        for (var key : keys) {
            var row = new ArrayList<Object>(2);
            row.add(key);
            row.add(sampleValue(jedis, key));
            rows.add(row);
        }
        return resultMapper.rows(List.of(RedisResultMapper.KEY_COLUMN, RedisResultMapper.VALUE_COLUMN),
                rows, truncated, duration, restricted, masks);
    }

    private SelectExecutionResult sampleHashes(JedisPooled jedis, List<String> keys,
                                               boolean truncated, Duration duration,
                                               List<String> restricted,
                                               List<ColumnMaskDirective> masks) {
        var hashes = new ArrayList<Map<String, String>>(keys.size());
        var columnNames = new LinkedHashSet<String>();
        for (var key : keys) {
            var hash = jedis.hgetAll(key);
            hashes.add(hash);
            columnNames.addAll(hash.keySet());
        }
        var columns = new ArrayList<>(columnNames);
        var rows = new ArrayList<List<Object>>(hashes.size());
        for (var hash : hashes) {
            var row = new ArrayList<Object>(columns.size());
            for (var column : columns) {
                row.add(hash.get(column));
            }
            rows.add(row);
        }
        return resultMapper.rows(columns, rows, truncated, duration, restricted, masks);
    }

    private static String sampleValue(JedisPooled jedis, String key) {
        return switch (jedis.type(key)) {
            case "string" -> jedis.get(key);
            case "list" -> String.valueOf(jedis.lrange(key, 0, -1));
            case "set" -> String.valueOf(jedis.smembers(key));
            case "zset" -> String.valueOf(jedis.zrange(key, 0, -1));
            default -> null;
        };
    }

    private static List<String> scanKeys(JedisPooled jedis, String prefix, int limit) {
        var keys = new ArrayList<String>();
        var params = new ScanParams().match(prefix + ":*").count(200);
        var cursor = ScanParams.SCAN_POINTER_START;
        do {
            var result = jedis.scan(cursor, params);
            keys.addAll(result.getResult());
            cursor = result.getCursor();
        } while (!ScanParams.SCAN_POINTER_START.equals(cursor) && keys.size() < limit);
        return keys.size() > limit ? new ArrayList<>(keys.subList(0, limit)) : keys;
    }

    private void failClosedForPrefix(String prefix, List<RowSecurityDirective> directives) {
        if (directives == null || directives.isEmpty()) {
            return;
        }
        var prefixes = Set.of(prefix.toLowerCase(Locale.ROOT).trim());
        for (var directive : directives) {
            if (matchingPrefix(directive.tableRef(), prefixes) != null) {
                throw new UnrewritableRowSecurityException(
                        messages.get("error.row_security_redis_unsupported", prefix));
            }
        }
    }

    // ---- row-security fail-closed ---------------------------------------------------------------

    private void failClosedOnRowSecurity(ParsedRedisCommand parsed,
                                         List<RowSecurityDirective> directives) {
        if (directives == null || directives.isEmpty()) {
            return;
        }
        for (var directive : directives) {
            var prefix = matchingPrefix(directive.tableRef(), parsed.keyPrefixes());
            if (prefix != null) {
                throw new UnrewritableRowSecurityException(
                        messages.get("error.row_security_redis_unsupported", prefix));
            }
        }
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

    // ---- shaping helpers ------------------------------------------------------------------------

    private QueryExecutionResult scan(JedisPooled jedis, ParsedRedisCommand p, int maxRows,
                                      List<String> restricted,
                                      List<com.bablsoft.accessflow.core.api.ColumnMaskDirective> masks,
                                      Instant start) {
        var params = new ScanParams();
        for (int i = 1; i < p.argCount() - 1; i++) {
            var token = p.arg(i).toUpperCase(Locale.ROOT);
            if (token.equals("MATCH")) {
                params.match(p.arg(++i));
            } else if (token.equals("COUNT")) {
                params.count((int) l(p.arg(++i)));
            }
        }
        var result = jedis.scan(p.arg(0), params);
        boolean more = !ScanParams.SCAN_POINTER_START.equals(result.getCursor());
        return resultMapper.keys(result.getResult(), more, maxRows, dur(start), restricted, masks);
    }

    private QueryExecutionResult zrange(JedisPooled jedis, ParsedRedisCommand p, String key, int maxRows,
                                        List<String> restricted,
                                        List<com.bablsoft.accessflow.core.api.ColumnMaskDirective> masks,
                                        Instant start) {
        boolean withScores = p.argCount() >= 4 && p.arg(3).equalsIgnoreCase("WITHSCORES");
        if (!withScores) {
            return resultMapper.collection(jedis.zrange(key, l(p.arg(1)), l(p.arg(2))), maxRows,
                    dur(start), restricted, masks);
        }
        var tuples = jedis.zrangeWithScores(key, l(p.arg(1)), l(p.arg(2)));
        var rows = new ArrayList<List<Object>>(tuples.size());
        for (Tuple tuple : tuples) {
            rows.add(List.of(tuple.getElement(), tuple.getScore()));
        }
        boolean truncated = rows.size() > maxRows;
        var capped = truncated ? rows.subList(0, maxRows) : rows;
        return resultMapper.rows(List.of("member", "score"), capped, truncated, dur(start),
                restricted, masks);
    }

    private QueryExecutionResult pop(List<String> list, String single, int maxRows,
                                     List<String> restricted,
                                     List<com.bablsoft.accessflow.core.api.ColumnMaskDirective> masks,
                                     Instant start) {
        if (list != null) {
            return resultMapper.collection(list, maxRows, dur(start), restricted, masks);
        }
        return resultMapper.singleValue(single, dur(start), restricted, masks);
    }

    private QueryExecutionResult select(QueryExecutionResult result) {
        return result;
    }

    private QueryExecutionResult scalar(Object value, Instant start, List<String> restricted,
                                        List<com.bablsoft.accessflow.core.api.ColumnMaskDirective> masks) {
        return resultMapper.singleValue(value, dur(start), restricted, masks);
    }

    private UpdateExecutionResult count(long count, Instant start) {
        return new UpdateExecutionResult(count, dur(start));
    }

    private UpdateExecutionResult status(String reply, Instant start) {
        return new UpdateExecutionResult("OK".equalsIgnoreCase(reply) ? 1 : 0, dur(start));
    }

    private static long hset(JedisPooled jedis, ParsedRedisCommand p, String key) {
        if (p.argCount() == 3) {
            return jedis.hset(key, p.arg(1), p.arg(2));
        }
        return jedis.hset(key, pairMap(p, 1));
    }

    private static long zadd(JedisPooled jedis, ParsedRedisCommand p, String key) {
        if (p.argCount() == 3) {
            return jedis.zadd(key, Double.parseDouble(p.arg(1)), p.arg(2));
        }
        var scoreMembers = new LinkedHashMap<String, Double>();
        for (int i = 1; i + 1 < p.argCount(); i += 2) {
            scoreMembers.put(p.arg(i + 1), Double.parseDouble(p.arg(i)));
        }
        return jedis.zadd(key, scoreMembers);
    }

    private static LinkedHashMap<String, String> pairMap(ParsedRedisCommand p, int from) {
        var map = new LinkedHashMap<String, String>();
        for (int i = from; i + 1 < p.argCount(); i += 2) {
            map.put(p.arg(i), p.arg(i + 1));
        }
        return map;
    }

    private static String[] values(ParsedRedisCommand p, int from) {
        return p.args().subList(from, p.argCount()).toArray(new String[0]);
    }

    private static List<String> nullableList(String value) {
        var list = new ArrayList<String>(1);
        list.add(value);
        return list;
    }

    private static long l(String value) {
        return Long.parseLong(value.strip());
    }

    private static double d(String value) {
        return Double.parseDouble(value.strip());
    }

    private Duration dur(Instant start) {
        return Duration.between(start, clock.instant());
    }
}
