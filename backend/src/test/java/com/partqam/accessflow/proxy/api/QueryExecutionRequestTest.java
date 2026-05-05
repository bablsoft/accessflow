package com.partqam.accessflow.proxy.api;

import com.partqam.accessflow.core.api.QueryType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryExecutionRequestTest {

    private final UUID datasourceId = UUID.randomUUID();

    @Test
    void allFieldsSetIsAccepted() {
        var request = new QueryExecutionRequest(datasourceId, "SELECT 1",
                QueryType.SELECT, 100, Duration.ofSeconds(5));

        assertThat(request.datasourceId()).isEqualTo(datasourceId);
        assertThat(request.sql()).isEqualTo("SELECT 1");
        assertThat(request.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(request.maxRowsOverride()).isEqualTo(100);
        assertThat(request.statementTimeoutOverride()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void nullableOverridesAreAllowed() {
        var request = new QueryExecutionRequest(datasourceId, "SELECT 1",
                QueryType.SELECT, null, null);

        assertThat(request.maxRowsOverride()).isNull();
        assertThat(request.statementTimeoutOverride()).isNull();
    }

    @Test
    void nullDatasourceIdIsRejected() {
        assertThatThrownBy(() -> new QueryExecutionRequest(null, "SELECT 1",
                QueryType.SELECT, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("datasourceId");
    }

    @Test
    void nullQueryTypeIsRejected() {
        assertThatThrownBy(() -> new QueryExecutionRequest(datasourceId, "SELECT 1",
                null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("queryType");
    }

    @Test
    void nullSqlIsRejected() {
        assertThatThrownBy(() -> new QueryExecutionRequest(datasourceId, null,
                QueryType.SELECT, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sql must not be blank");
    }

    @Test
    void blankSqlIsRejected() {
        assertThatThrownBy(() -> new QueryExecutionRequest(datasourceId, "   ",
                QueryType.SELECT, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sql must not be blank");
    }

    @Test
    void zeroMaxRowsOverrideIsRejected() {
        assertThatThrownBy(() -> new QueryExecutionRequest(datasourceId, "SELECT 1",
                QueryType.SELECT, 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRowsOverride must be positive");
    }

    @Test
    void negativeMaxRowsOverrideIsRejected() {
        assertThatThrownBy(() -> new QueryExecutionRequest(datasourceId, "SELECT 1",
                QueryType.SELECT, -1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRowsOverride must be positive");
    }

    @Test
    void zeroTimeoutOverrideIsRejected() {
        assertThatThrownBy(() -> new QueryExecutionRequest(datasourceId, "SELECT 1",
                QueryType.SELECT, null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("statementTimeoutOverride must be positive");
    }

    @Test
    void negativeTimeoutOverrideIsRejected() {
        assertThatThrownBy(() -> new QueryExecutionRequest(datasourceId, "SELECT 1",
                QueryType.SELECT, null, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("statementTimeoutOverride must be positive");
    }
}
