package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.lifecycle.api.ErasureCondition;
import com.bablsoft.accessflow.lifecycle.api.ErasureConditionSet;
import com.bablsoft.accessflow.lifecycle.api.InvalidErasureConfigException;
import com.bablsoft.accessflow.lifecycle.api.LifecycleSubjectType;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ErasurePredicateCompilerTest {

    private static final UUID ID = UUID.randomUUID();

    @Mock SqlParserService sqlParserService;
    @InjectMocks ErasurePredicateCompiler compiler;

    @Test
    void subjectOnly_emitsSingleBoundEqualsDirective() {
        var c = compiler.compile(ID, "users", LifecycleSubjectType.EMAIL, "user@example.com",
                null, null, null, null);
        assertThat(c.whereClause()).isNull();
        assertThat(c.directives()).singleElement().satisfies(d -> {
            assertThat(d.columnName()).isEqualTo("email");
            assertThat(d.operator()).isEqualTo(RowSecurityOperator.EQUALS);
            assertThat(d.values()).containsExactly("user@example.com");
        });
    }

    @Test
    void structuredConditions_compileToBoundDirectives() {
        var set = new ErasureConditionSet(List.of(
                new ErasureCondition("status", RowSecurityOperator.EQUALS, List.of("inactive"), false),
                new ErasureCondition("deleted_at", RowSecurityOperator.IS_NULL, List.of(), false)));
        var c = compiler.compile(ID, "users", null, null, set, null, null, null);
        assertThat(c.directives()).hasSize(2);
        assertThat(c.directives().get(1).operator()).isEqualTo(RowSecurityOperator.IS_NULL);
    }

    @Test
    void negatedCondition_flipsToComplementaryOperator() {
        var set = new ErasureConditionSet(List.of(
                new ErasureCondition("region", RowSecurityOperator.IN, List.of("EU"), true)));
        var c = compiler.compile(ID, "users", null, null, set, null, null, null);
        assertThat(c.directives()).singleElement()
                .satisfies(d -> assertThat(d.operator()).isEqualTo(RowSecurityOperator.NOT_IN));
    }

    @Test
    void arityMismatch_isRejected() {
        var set = new ErasureConditionSet(List.of(
                new ErasureCondition("status", RowSecurityOperator.EQUALS, List.of(), false)));
        assertThatThrownBy(() -> compiler.compile(ID, "users", null, null, set, null, null, null))
                .isInstanceOf(InvalidErasureConfigException.class);
    }

    @Test
    void retentionWindow_emitsInlinedTimestampClause() {
        var cutoff = ZonedDateTime.of(2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        var c = compiler.compile(ID, "events", null, null, null, null, "created_at", cutoff);
        assertThat(c.whereClause()).contains("created_at <").contains("2020-01-01");
    }

    @Test
    void rawWhere_isValidatedAndInlined() {
        when(sqlParserService.parse(anyString()))
                .thenReturn(new SqlParseResult(QueryType.SELECT, "x"));
        var c = compiler.compile(ID, "users", null, null, null, "status = 'x'", null, null);
        assertThat(c.whereClause()).isEqualTo("(status = 'x')");
    }

    @Test
    void rawWhere_unparseable_isRejected() {
        when(sqlParserService.parse(anyString())).thenThrow(new InvalidSqlException("bad"));
        assertThatThrownBy(() -> compiler.compile(ID, "users", null, null, null, "!!!", null, null))
                .isInstanceOf(InvalidErasureConfigException.class);
    }
}
