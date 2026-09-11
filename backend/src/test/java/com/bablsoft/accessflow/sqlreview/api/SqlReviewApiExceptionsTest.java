package com.bablsoft.accessflow.sqlreview.api;

import com.bablsoft.accessflow.core.api.DatasourceEnvironment;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SqlReviewApiExceptionsTest {

    @Test
    void notFoundCarriesRulesetId() {
        var id = UUID.randomUUID();
        var ex = new SqlReviewRulesetNotFoundException(id);
        assertThat(ex.rulesetId()).isEqualTo(id);
        assertThat(ex.getMessage()).contains(id.toString());
    }

    @Test
    void environmentConflictCarriesEnvironment() {
        var ex = new SqlReviewRulesetConflictException(DatasourceEnvironment.PRODUCTION);
        assertThat(ex.environment()).isEqualTo(DatasourceEnvironment.PRODUCTION);
        assertThat(ex.getMessage()).contains("PRODUCTION");
    }

    @Test
    void defaultConflictHasNullEnvironment() {
        var ex = new SqlReviewRulesetConflictException(null);
        assertThat(ex.environment()).isNull();
        assertThat(ex.getMessage()).contains("default");
    }
}
