package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.InvalidSqlException;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.lifecycle.api.ErasureCondition;
import com.bablsoft.accessflow.lifecycle.api.ErasureConditionSet;
import com.bablsoft.accessflow.lifecycle.api.InvalidErasureConfigException;
import com.bablsoft.accessflow.proxy.api.SqlParserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ErasureConditionValidatorTest {

    private static final UUID DS = UUID.randomUUID();

    @Mock DatasourceLookupService datasourceLookupService;
    @Mock SqlParserService sqlParserService;
    @InjectMocks ErasureConditionValidator validator;

    private void datasourceType(DbType type) {
        lenient().when(datasourceLookupService.findById(DS))
                .thenReturn(Optional.of(descriptor(type)));
    }

    @Test
    void acceptsNullConfig() {
        assertThatCode(() -> validator.validate(DS, null, null, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsConditionsOnNonSqlDatasource() {
        datasourceType(DbType.MONGODB);
        var set = new ErasureConditionSet(List.of(
                new ErasureCondition("c", RowSecurityOperator.EQUALS, List.of("v"), false)));
        assertThatThrownBy(() -> validator.validate(DS, "t", set, null, null))
                .isInstanceOf(InvalidErasureConfigException.class)
                .extracting(e -> ((InvalidErasureConfigException) e).reason())
                .isEqualTo(InvalidErasureConfigException.Reason.UNSUPPORTED_DATASOURCE);
    }

    @Test
    void rejectsArityMismatch() {
        datasourceType(DbType.POSTGRESQL);
        var set = new ErasureConditionSet(List.of(
                new ErasureCondition("c", RowSecurityOperator.IN, List.of(), false)));
        assertThatThrownBy(() -> validator.validate(DS, "t", set, null, null))
                .isInstanceOf(InvalidErasureConfigException.class)
                .extracting(e -> ((InvalidErasureConfigException) e).reason())
                .isEqualTo(InvalidErasureConfigException.Reason.CONDITION_VALUE_ARITY);
    }

    @Test
    void rejectsUnparseableRawWhere() {
        datasourceType(DbType.POSTGRESQL);
        when(sqlParserService.parse(anyString())).thenThrow(new InvalidSqlException("bad"));
        assertThatThrownBy(() -> validator.validate(DS, "t", null, "!!!", null))
                .isInstanceOf(InvalidErasureConfigException.class)
                .extracting(e -> ((InvalidErasureConfigException) e).reason())
                .isEqualTo(InvalidErasureConfigException.Reason.INVALID_RAW_WHERE);
    }

    @Test
    void rejectsInvalidCron() {
        assertThatThrownBy(() -> validator.validate(DS, null, null, null, "not a cron"))
                .isInstanceOf(InvalidErasureConfigException.class)
                .extracting(e -> ((InvalidErasureConfigException) e).reason())
                .isEqualTo(InvalidErasureConfigException.Reason.INVALID_CRON);
    }

    @Test
    void acceptsValidSqlConfigAndCron() {
        datasourceType(DbType.POSTGRESQL);
        when(sqlParserService.parse(anyString())).thenReturn(new SqlParseResult(QueryType.SELECT, "x"));
        var set = new ErasureConditionSet(List.of(
                new ErasureCondition("status", RowSecurityOperator.EQUALS, List.of("x"), false)));
        assertThatCode(() -> validator.validate(DS, "t", set, "a = 1", "0 0 3 * * *"))
                .doesNotThrowAnyException();
    }

    private static DatasourceConnectionDescriptor descriptor(DbType type) {
        return new DatasourceConnectionDescriptor(DS, UUID.randomUUID(), type, "h", 5432, "db",
                "u", "enc", null, 10, 1000, false, null, false, null, null, null, null, null, null,
                true, null, null);
    }
}
