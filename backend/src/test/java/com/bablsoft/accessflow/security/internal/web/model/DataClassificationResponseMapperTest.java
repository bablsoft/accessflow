package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.DataClassificationDerivationView;
import com.bablsoft.accessflow.core.api.DataClassificationTagView;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.OrganizationDataClassificationView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DataClassificationResponseMapperTest {

    @Test
    void tagResponseMapsAllFields() {
        var id = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var now = Instant.now();
        var view = new DataClassificationTagView(id, datasourceId, "users", "email",
                DataClassification.PII, "note", now, now);

        var response = DataClassificationTagResponse.from(view);

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.datasourceId()).isEqualTo(datasourceId);
        assertThat(response.tableName()).isEqualTo("users");
        assertThat(response.columnName()).isEqualTo("email");
        assertThat(response.classification()).isEqualTo(DataClassification.PII);
        assertThat(response.note()).isEqualTo("note");
    }

    @Test
    void organizationResponseMapsDatasourceName() {
        var view = new OrganizationDataClassificationView(UUID.randomUUID(), UUID.randomUUID(),
                "Prod DB", "users", null, DataClassification.PCI, null, Instant.now(), Instant.now());

        var response = OrganizationDataClassificationResponse.from(view);

        assertThat(response.datasourceName()).isEqualTo("Prod DB");
        assertThat(response.columnName()).isNull();
        assertThat(response.classification()).isEqualTo(DataClassification.PCI);
    }

    @Test
    void derivationResponseMapsPostureAndSuggestions() {
        var posture = new DataClassificationDerivationView.ReviewPosture(true, true, 2,
                List.of(DataClassification.PII, DataClassification.PCI));
        var suggestion = new DataClassificationDerivationView.MaskingSuggestion("users.email",
                DataClassification.PII, MaskingStrategy.PARTIAL, Map.of("visible_suffix", "4"), false);
        var view = new DataClassificationDerivationView(posture, List.of(suggestion));

        var response = DataClassificationDerivationResponse.from(view);

        assertThat(response.suggestedReviewPosture().requiresAiReview()).isTrue();
        assertThat(response.suggestedReviewPosture().minApprovals()).isEqualTo(2);
        assertThat(response.suggestedReviewPosture().drivenBy())
                .containsExactly(DataClassification.PII, DataClassification.PCI);
        assertThat(response.maskingSuggestions()).hasSize(1);
        assertThat(response.maskingSuggestions().getFirst().columnRef()).isEqualTo("users.email");
        assertThat(response.maskingSuggestions().getFirst().suggestedStrategy())
                .isEqualTo(MaskingStrategy.PARTIAL);
        assertThat(response.maskingSuggestions().getFirst().alreadyApplied()).isFalse();
    }
}
