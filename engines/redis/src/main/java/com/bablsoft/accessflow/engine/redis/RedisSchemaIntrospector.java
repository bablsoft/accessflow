package com.bablsoft.accessflow.engine.redis;

import com.bablsoft.accessflow.core.api.CredentialDecryptor;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionTestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.params.ScanParams;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Infers a schema for a Redis datasource by SCAN-sampling a bounded number of keys and grouping
 * them by key prefix (text before the first {@code :}) into pseudo-tables. The value type of a
 * sample key per group (string / hash / list / set / zset) drives the column shape: a hash exposes
 * its field names as columns, every other type a single synthetic {@code value} column typed by the
 * Redis value type. Returns the same {@link DatabaseSchemaView} shape as the JDBC path (schema =
 * {@code db<index>}, tables = key-prefix groups, no primary keys, no foreign keys) so the ER
 * diagram, editor autocomplete, and AI schema context work unchanged.
 */
class RedisSchemaIntrospector {

    private static final Logger log = LoggerFactory.getLogger(RedisSchemaIntrospector.class);
    private static final int SCAN_LIMIT = 1000;
    private static final int SCAN_BATCH = 200;
    private static final int MAX_HASH_FIELDS = 50;

    private final CredentialDecryptor credentials;
    private final RedisEngineSettings settings;

    RedisSchemaIntrospector(CredentialDecryptor credentials, RedisEngineSettings settings) {
        this.credentials = credentials;
        this.settings = settings;
    }

    DatabaseSchemaView introspect(DatasourceConnectionDescriptor descriptor) {
        var schemaName = "db" + RedisConnectionFactory.resolveDatabase(descriptor);
        try (var client = RedisConnectionFactory.open(descriptor, credentials, settings)) {
            var sampleKeyByPrefix = sampleKeys(client);
            var tables = new ArrayList<DatabaseSchemaView.Table>(sampleKeyByPrefix.size());
            for (var entry : sampleKeyByPrefix.entrySet()) {
                tables.add(new DatabaseSchemaView.Table(entry.getKey(),
                        columnsFor(client, entry.getValue()), List.of()));
            }
            return new DatabaseSchemaView(
                    List.of(new DatabaseSchemaView.Schema(schemaName, tables)));
        } catch (JedisException | IllegalArgumentException e) {
            log.warn("Redis schema introspection failed for datasource {}: {}", descriptor.id(),
                    e.getMessage());
            throw new DatasourceConnectionTestException(e.getMessage());
        }
    }

    /** SCAN up to {@link #SCAN_LIMIT} keys; keep the first key seen per prefix as the group sample. */
    private Map<String, String> sampleKeys(JedisPooled client) {
        var sampleByPrefix = new LinkedHashMap<String, String>();
        var params = new ScanParams().count(SCAN_BATCH);
        var cursor = ScanParams.SCAN_POINTER_START;
        int seen = 0;
        do {
            var result = client.scan(cursor, params);
            for (var key : result.getResult()) {
                sampleByPrefix.putIfAbsent(prefixOf(key), key);
                seen++;
            }
            cursor = result.getCursor();
        } while (!ScanParams.SCAN_POINTER_START.equals(cursor) && seen < SCAN_LIMIT);
        return sampleByPrefix;
    }

    private List<DatabaseSchemaView.Column> columnsFor(JedisPooled client, String sampleKey) {
        var type = client.type(sampleKey);
        if ("hash".equals(type)) {
            var columns = new ArrayList<DatabaseSchemaView.Column>();
            for (var field : client.hkeys(sampleKey)) {
                if (columns.size() >= MAX_HASH_FIELDS) {
                    break;
                }
                columns.add(new DatabaseSchemaView.Column(field, "string", true, false));
            }
            return columns;
        }
        return List.of(new DatabaseSchemaView.Column(RedisResultMapper.VALUE_COLUMN,
                type == null || "none".equals(type) ? "string" : type, true, false));
    }

    private static String prefixOf(String key) {
        int colon = key.indexOf(':');
        return (colon >= 0 ? key.substring(0, colon) : key).toLowerCase(Locale.ROOT);
    }
}
