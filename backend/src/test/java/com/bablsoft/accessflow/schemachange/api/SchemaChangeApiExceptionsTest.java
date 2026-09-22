package com.bablsoft.accessflow.schemachange.api;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaChangeApiExceptionsTest {

    @Test
    void changeSetNotFoundCarriesTheId() {
        var id = UUID.randomUUID();

        var ex = new SchemaChangeSetNotFoundException(id);

        assertThat(ex).isInstanceOf(SchemaChangeException.class);
        assertThat(ex.changeSetId()).isEqualTo(id);
        assertThat(ex.getMessage()).contains(id.toString());
    }

    @Test
    void promotionNotFoundCarriesTheId() {
        var id = UUID.randomUUID();

        var ex = new SchemaChangePromotionNotFoundException(id);

        assertThat(ex).isInstanceOf(SchemaChangeException.class);
        assertThat(ex.promotionId()).isEqualTo(id);
        assertThat(ex.getMessage()).contains(id.toString());
    }

    @Test
    void driftFindingNotFoundCarriesTheId() {
        var id = UUID.randomUUID();

        var ex = new SchemaDriftFindingNotFoundException(id);

        assertThat(ex).isInstanceOf(SchemaChangeException.class);
        assertThat(ex.findingId()).isEqualTo(id);
        assertThat(ex.getMessage()).contains(id.toString());
    }

    @Test
    void nameConflictCarriesPipelineAndName() {
        var pipelineId = UUID.randomUUID();

        var ex = new SchemaChangeSetNameConflictException(pipelineId, "orders-v2");

        assertThat(ex).isInstanceOf(SchemaChangeException.class);
        assertThat(ex.pipelineId()).isEqualTo(pipelineId);
        assertThat(ex.name()).isEqualTo("orders-v2");
        assertThat(ex.getMessage()).contains(pipelineId.toString()).contains("orders-v2");
    }

    @Test
    void promotionConflictCarriesSetAndEnvironment() {
        var changeSetId = UUID.randomUUID();
        var environmentId = UUID.randomUUID();

        var ex = new SchemaChangePromotionConflictException(changeSetId, environmentId);

        assertThat(ex).isInstanceOf(SchemaChangeException.class);
        assertThat(ex.changeSetId()).isEqualTo(changeSetId);
        assertThat(ex.environmentId()).isEqualTo(environmentId);
        assertThat(ex.getMessage()).contains(changeSetId.toString()).contains(environmentId.toString());
    }
}
