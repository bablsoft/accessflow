package com.bablsoft.accessflow.schemachange.events;

import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaChangePromotionStatusChangedEventTest {

    @Test
    void carriesEveryComponent() {
        var promotionId = UUID.randomUUID();
        var changeSetId = UUID.randomUUID();
        var environmentId = UUID.randomUUID();
        var organizationId = UUID.randomUUID();
        var promotedBy = UUID.randomUUID();

        var event = new SchemaChangePromotionStatusChangedEvent(promotionId, changeSetId, environmentId,
                organizationId, promotedBy, SchemaChangePromotionStatus.APPROVED, SchemaChangePromotionStatus.APPLIED);

        assertThat(event).extracting("promotionId", "changeSetId", "environmentId", "organizationId", "promotedBy",
                        "oldStatus", "newStatus")
                .containsExactly(promotionId, changeSetId, environmentId, organizationId, promotedBy,
                        SchemaChangePromotionStatus.APPROVED, SchemaChangePromotionStatus.APPLIED);
    }

    @Test
    void submissionCarriesNoPreviousStatus() {
        var event = new SchemaChangePromotionStatusChangedEvent(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, SchemaChangePromotionStatus.PENDING);

        assertThat(event.oldStatus()).isNull();
        assertThat(event.newStatus()).isEqualTo(SchemaChangePromotionStatus.PENDING);
    }
}
