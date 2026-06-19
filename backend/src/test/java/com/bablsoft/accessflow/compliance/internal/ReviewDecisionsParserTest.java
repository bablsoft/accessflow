package com.bablsoft.accessflow.compliance.internal;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewDecisionsParserTest {

    private final ReviewDecisionsParser parser = new ReviewDecisionsParser(new ObjectMapper());

    @Test
    void parsesApprovedReviewersOnly() {
        var json = """
                [
                  {"reviewer":{"email":"a@x.com","displayName":"Alice"},"decision":"APPROVED",
                   "decidedAt":"2026-06-18T11:00:00Z"},
                  {"reviewer":{"email":"b@x.com","displayName":"Bob"},"decision":"REJECTED",
                   "decidedAt":"2026-06-18T12:00:00Z"}
                ]
                """;

        var approvers = parser.approvers(json);

        assertThat(approvers).hasSize(1);
        assertThat(approvers.getFirst().email()).isEqualTo("a@x.com");
        assertThat(approvers.getFirst().displayName()).isEqualTo("Alice");
        assertThat(approvers.getFirst().decision()).isEqualTo("APPROVED");
        assertThat(approvers.getFirst().decidedAt()).isEqualTo(Instant.parse("2026-06-18T11:00:00Z"));
    }

    @Test
    void missingReviewerNodeYieldsNullNames() {
        var json = "[{\"decision\":\"APPROVED\",\"decidedAt\":\"2026-06-18T11:00:00Z\"}]";

        var approvers = parser.approvers(json);

        assertThat(approvers).hasSize(1);
        assertThat(approvers.getFirst().email()).isNull();
        assertThat(approvers.getFirst().displayName()).isNull();
    }

    @Test
    void invalidDecidedAtBecomesNull() {
        var json = "[{\"reviewer\":{\"email\":\"a@x.com\"},\"decision\":\"APPROVED\","
                + "\"decidedAt\":\"not-a-date\"}]";

        var approvers = parser.approvers(json);

        assertThat(approvers).hasSize(1);
        assertThat(approvers.getFirst().decidedAt()).isNull();
    }

    @Test
    void malformedJsonYieldsEmptyList() {
        assertThat(parser.approvers("{not valid")).isEmpty();
    }

    @Test
    void nonArrayJsonYieldsEmptyList() {
        assertThat(parser.approvers("{\"decision\":\"APPROVED\"}")).isEmpty();
    }

    @Test
    void nullOrBlankYieldsEmptyList() {
        assertThat(parser.approvers(null)).isEmpty();
        assertThat(parser.approvers("  ")).isEmpty();
    }
}
