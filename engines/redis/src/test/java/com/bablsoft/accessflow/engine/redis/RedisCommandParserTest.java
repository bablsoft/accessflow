package com.bablsoft.accessflow.engine.redis;

import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisCommandParserTest {

    private final RedisCommandParser parser = new RedisCommandParser(TestMessages.keyEcho());

    @Test
    void classifiesReadAsSelectAndExtractsKeyPrefix() {
        var result = parser.parse("GET user:42");
        assertThat(result.type()).isEqualTo(QueryType.SELECT);
        assertThat(result.referencedTables()).containsExactly("user");
        assertThat(result.transactional()).isFalse();
    }

    @Test
    void classifiesWritesDeletesAndAdmin() {
        assertThat(parser.parse("SET user:1 ada").type()).isEqualTo(QueryType.UPDATE);
        assertThat(parser.parse("SETNX user:1 ada").type()).isEqualTo(QueryType.INSERT);
        assertThat(parser.parse("DEL user:1").type()).isEqualTo(QueryType.DELETE);
        assertThat(parser.parse("FLUSHDB").type()).isEqualTo(QueryType.DDL);
    }

    @Test
    void bareKeyAndNoColonPrefixAreThemselves() {
        assertThat(parser.parse("INCR counter").referencedTables()).containsExactly("counter");
        assertThat(parser.parse("GET Orders:7").referencedTables()).containsExactly("orders");
    }

    @Test
    void scanMatchPatternYieldsPrefixAndGlobOnlyYieldsNone() {
        assertThat(parser.parse("SCAN 0 MATCH orders:* COUNT 100").referencedTables())
                .containsExactly("orders");
        assertThat(parser.parse("KEYS *").referencedTables()).isEmpty();
        assertThat(parser.parse("SCAN 0").referencedTables()).isEmpty();
    }

    @Test
    void twoKeyAndMultiKeyCommandsContributeAllPrefixes() {
        assertThat(parser.parse("COPY src:1 dst:2").referencedTables())
                .containsExactlyInAnyOrder("src", "dst");
        assertThat(parser.parse("MGET a:1 b:2 c:3").referencedTables())
                .containsExactlyInAnyOrder("a", "b", "c");
        assertThat(parser.parse("MSET a:1 v1 b:2 v2").referencedTables())
                .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void honoursQuotedArguments() {
        var parsed = parser.parseCommand("SET greeting \"hello world\"");
        assertThat(parsed.args()).containsExactly("greeting", "hello world");
    }

    @Test
    void rejectsForbiddenCommands() {
        assertThatThrownBy(() -> parser.parse("EVAL \"return 1\" 0"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.redis.forbidden_command");
        assertThatThrownBy(() -> parser.parse("FLUSHALL"))
                .hasMessageContaining("error.redis.forbidden_command");
        assertThatThrownBy(() -> parser.parse("CONFIG GET maxmemory"))
                .hasMessageContaining("error.redis.forbidden_command");
    }

    @Test
    void rejectsUnknownCommands() {
        assertThatThrownBy(() -> parser.parse("FROBNICATE key"))
                .isInstanceOf(InvalidSqlException.class)
                .hasMessageContaining("error.redis.unsupported_command");
    }

    @Test
    void rejectsBlankMultiCommandUnbalancedAndMissingArgs() {
        assertThatThrownBy(() -> parser.parse("   ")).hasMessageContaining("error.redis.blank");
        assertThatThrownBy(() -> parser.parse("GET a\nGET b"))
                .hasMessageContaining("error.redis.multiple_commands");
        assertThatThrownBy(() -> parser.parse("SET k \"unterminated"))
                .hasMessageContaining("error.redis.unbalanced");
        assertThatThrownBy(() -> parser.parse("SET onlykey"))
                .hasMessageContaining("error.redis.argument_required");
    }

    @Test
    void isCaseInsensitiveOnCommandName() {
        assertThat(parser.parse("get user:1").type()).isEqualTo(QueryType.SELECT);
    }
}
