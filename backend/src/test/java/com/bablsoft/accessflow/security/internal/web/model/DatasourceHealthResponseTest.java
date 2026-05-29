package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.proxy.api.DatasourceHealthSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DatasourceHealthResponseTest {

    @Test
    void fromCopiesEveryFieldIncludingLivePoolGauges() {
        var id = UUID.randomUUID();
        var snapshot = new DatasourceHealthSnapshot(id, "prod", DbType.MYSQL, true,
                1, 2, 3, 3, 10, 99L, 11.0, 47.0, 4L);

        var response = DatasourceHealthResponse.from(snapshot);

        assertThat(response.datasourceId()).isEqualTo(id);
        assertThat(response.datasourceName()).isEqualTo("prod");
        assertThat(response.dbType()).isEqualTo(DbType.MYSQL);
        assertThat(response.active()).isTrue();
        assertThat(response.poolActive()).isEqualTo(1);
        assertThat(response.poolIdle()).isEqualTo(2);
        assertThat(response.poolWaiting()).isEqualTo(3);
        assertThat(response.poolTotal()).isEqualTo(3);
        assertThat(response.poolMax()).isEqualTo(10);
        assertThat(response.queriesLast24h()).isEqualTo(99L);
        assertThat(response.executionMsP50()).isEqualTo(11.0);
        assertThat(response.executionMsP95()).isEqualTo(47.0);
        assertThat(response.errorsLast24h()).isEqualTo(4L);
    }

    @Test
    void fromPreservesNullPoolGaugesAndPercentiles() {
        var snapshot = new DatasourceHealthSnapshot(UUID.randomUUID(), "idle", DbType.POSTGRESQL,
                false, null, null, null, null, null, 0L, null, null, 0L);

        var response = DatasourceHealthResponse.from(snapshot);

        assertThat(response.poolActive()).isNull();
        assertThat(response.poolMax()).isNull();
        assertThat(response.executionMsP50()).isNull();
        assertThat(response.executionMsP95()).isNull();
        assertThat(response.active()).isFalse();
    }

    @Test
    void pageResponseFromCopiesPaginationMetadata() {
        var item = DatasourceHealthResponse.from(new DatasourceHealthSnapshot(UUID.randomUUID(),
                "ds", DbType.ORACLE, true, null, null, null, null, null, 0L, null, null, 0L));
        var page = new PageResponse<>(List.of(item), 1, 25, 30L, 2);

        var response = DatasourceHealthPageResponse.from(page);

        assertThat(response.content()).containsExactly(item);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(25);
        assertThat(response.totalElements()).isEqualTo(30L);
        assertThat(response.totalPages()).isEqualTo(2);
    }
}
