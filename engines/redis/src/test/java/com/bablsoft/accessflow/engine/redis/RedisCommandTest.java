package com.bablsoft.accessflow.engine.redis;

import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisCommandTest {

    @Test
    void findIsCaseInsensitiveAndReturnsNullForUnknown() {
        assertThat(RedisCommand.find("hgetall")).isEqualTo(RedisCommand.HGETALL);
        assertThat(RedisCommand.find("HGETALL")).isEqualTo(RedisCommand.HGETALL);
        assertThat(RedisCommand.find("nope")).isNull();
        assertThat(RedisCommand.find(null)).isNull();
    }

    @Test
    void forbiddenSetCoversScriptingAndBlastRadius() {
        assertThat(RedisCommand.isForbidden("EVAL")).isTrue();
        assertThat(RedisCommand.isForbidden("eval")).isTrue();
        assertThat(RedisCommand.isForbidden("SCRIPT")).isTrue();
        assertThat(RedisCommand.isForbidden("FLUSHALL")).isTrue();
        assertThat(RedisCommand.isForbidden("SWAPDB")).isTrue();
        assertThat(RedisCommand.isForbidden("MULTI")).isTrue();
        assertThat(RedisCommand.isForbidden("SUBSCRIBE")).isTrue();
        assertThat(RedisCommand.isForbidden("GET")).isFalse();
    }

    @Test
    void allowListAndForbiddenSetAreDisjoint() {
        for (var command : RedisCommand.values()) {
            assertThat(RedisCommand.FORBIDDEN).doesNotContain(command.name());
        }
    }

    @Test
    void metadataIsConsistentPerCategory() {
        assertThat(RedisCommand.GET.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(RedisCommand.GET.resultKind()).isEqualTo(ResultKind.STRING);
        assertThat(RedisCommand.DEL.queryType()).isEqualTo(QueryType.DELETE);
        assertThat(RedisCommand.SETNX.queryType()).isEqualTo(QueryType.INSERT);
        assertThat(RedisCommand.FLUSHDB.queryType()).isEqualTo(QueryType.DDL);
        assertThat(RedisCommand.FLUSHDB.keyArity()).isEqualTo(KeyArity.NONE);
        assertThat(RedisCommand.GET.minArgs()).isEqualTo(1);
        assertThat(RedisCommand.HSET.minArgs()).isEqualTo(3);
    }
}
