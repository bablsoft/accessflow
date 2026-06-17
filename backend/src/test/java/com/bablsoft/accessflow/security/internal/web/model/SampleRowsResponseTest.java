package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.ResultColumn;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import org.junit.jupiter.api.Test;

import java.sql.Types;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SampleRowsResponseTest {

    @Test
    void mapsColumnsRowsAndMetadataFromSelectResult() {
        var result = new SelectExecutionResult(
                List.of(new ResultColumn("id", Types.OTHER, "uuid", false),
                        new ResultColumn("email", Types.VARCHAR, "varchar", true)),
                List.of(List.of("1", "***"), List.of("2", "***")),
                2, true, Duration.ofMillis(42),
                java.util.Set.of(UUID.randomUUID()), java.util.Set.of());

        var response = SampleRowsResponse.from(result);

        assertThat(response.columns()).containsExactly(
                new SampleRowsResponse.Column("id", "uuid", false),
                new SampleRowsResponse.Column("email", "varchar", true));
        assertThat(response.rows()).isEqualTo(List.of(List.of("1", "***"), List.of("2", "***")));
        assertThat(response.rowCount()).isEqualTo(2);
        assertThat(response.truncated()).isTrue();
        assertThat(response.durationMs()).isEqualTo(42);
    }

    @Test
    void mapsEmptyResult() {
        var response = SampleRowsResponse.from(
                new SelectExecutionResult(List.of(), List.of(), 0, false, Duration.ZERO));

        assertThat(response.columns()).isEmpty();
        assertThat(response.rows()).isEmpty();
        assertThat(response.rowCount()).isZero();
        assertThat(response.truncated()).isFalse();
        assertThat(response.durationMs()).isZero();
    }
}
