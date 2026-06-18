package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuotaExceededExceptionTest {

    @Test
    void carriesQuotaTypeLimitAndCurrent() {
        var orgId = UUID.randomUUID();
        var ex = new QuotaExceededException(QuotaType.DATASOURCE, orgId, 5, 5);

        assertThat(ex.quotaType()).isEqualTo(QuotaType.DATASOURCE);
        assertThat(ex.organizationId()).isEqualTo(orgId);
        assertThat(ex.limit()).isEqualTo(5);
        assertThat(ex.current()).isEqualTo(5L);
        assertThat(ex.getMessage()).contains("DATASOURCE").contains(orgId.toString());
    }
}
