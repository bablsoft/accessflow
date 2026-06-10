package com.bablsoft.accessflow.proxy.internal.mongo;

import com.bablsoft.accessflow.core.api.QueryType;

import java.util.Locale;
import java.util.Map;

/**
 * The MongoDB operations AccessFlow supports, each mapped onto the engine-neutral {@link QueryType}
 * used by the permission model (read → {@code canRead}, write → {@code canWrite}, ddl →
 * {@code canDdl}). The {@code shellName} is the {@code db.collection.<name>(...)} method; the
 * {@code commandKey} is the leading key of the equivalent JSON command document
 * ({@code { "<key>": "collection", ... }}).
 */
enum MongoOperation {
    FIND(QueryType.SELECT, "find", "find"),
    AGGREGATE(QueryType.SELECT, "aggregate", "aggregate"),
    COUNT_DOCUMENTS(QueryType.SELECT, "countDocuments", "count"),
    DISTINCT(QueryType.SELECT, "distinct", "distinct"),
    INSERT_ONE(QueryType.INSERT, "insertOne", null),
    INSERT_MANY(QueryType.INSERT, "insertMany", "insert"),
    UPDATE_ONE(QueryType.UPDATE, "updateOne", null),
    UPDATE_MANY(QueryType.UPDATE, "updateMany", "update"),
    REPLACE_ONE(QueryType.UPDATE, "replaceOne", null),
    FIND_ONE_AND_UPDATE(QueryType.UPDATE, "findOneAndUpdate", "findAndModify"),
    DELETE_ONE(QueryType.DELETE, "deleteOne", null),
    DELETE_MANY(QueryType.DELETE, "deleteMany", "delete"),
    CREATE_COLLECTION(QueryType.DDL, "createCollection", "create"),
    CREATE_INDEX(QueryType.DDL, "createIndex", "createIndexes"),
    DROP_COLLECTION(QueryType.DDL, "drop", "drop"),
    DROP_INDEX(QueryType.DDL, "dropIndex", "dropIndexes");

    private static final Map<String, MongoOperation> BY_SHELL;
    private static final Map<String, MongoOperation> BY_COMMAND;

    static {
        var byShell = new java.util.HashMap<String, MongoOperation>();
        var byCommand = new java.util.HashMap<String, MongoOperation>();
        for (var op : values()) {
            byShell.put(op.shellName.toLowerCase(Locale.ROOT), op);
            if (op.commandKey != null) {
                byCommand.put(op.commandKey.toLowerCase(Locale.ROOT), op);
            }
        }
        // Convenience command aliases mirroring the mongo shell helpers.
        byCommand.put("insertone", INSERT_ONE);
        byCommand.put("updateone", UPDATE_ONE);
        byCommand.put("replaceone", REPLACE_ONE);
        byCommand.put("deleteone", DELETE_ONE);
        byCommand.put("aggregate", AGGREGATE);
        BY_SHELL = Map.copyOf(byShell);
        BY_COMMAND = Map.copyOf(byCommand);
    }

    private final QueryType queryType;
    private final String shellName;
    private final String commandKey;

    MongoOperation(QueryType queryType, String shellName, String commandKey) {
        this.queryType = queryType;
        this.shellName = shellName;
        this.commandKey = commandKey;
    }

    QueryType queryType() {
        return queryType;
    }

    boolean isRead() {
        return queryType == QueryType.SELECT;
    }

    boolean isWrite() {
        return queryType == QueryType.INSERT
                || queryType == QueryType.UPDATE
                || queryType == QueryType.DELETE;
    }

    static MongoOperation fromShell(String name) {
        return name == null ? null : BY_SHELL.get(name.toLowerCase(Locale.ROOT));
    }

    static MongoOperation fromCommandKey(String key) {
        return key == null ? null : BY_COMMAND.get(key.toLowerCase(Locale.ROOT));
    }
}
