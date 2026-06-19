package com.bablsoft.accessflow.compliance.internal;

import com.bablsoft.accessflow.compliance.api.Approver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the {@code review_decisions} JSON array carried on a query snapshot (serialized
 * {@code QueryDetailView.ReviewDecisionView[]}) into {@link Approver} rows for the regulatory audit
 * trail (#459). Tolerant by design: malformed or schema-drifted JSON yields an empty list rather
 * than throwing, since the forensic snapshot must never block a report.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class ReviewDecisionsParser {

    private static final String DECISION_APPROVED = "APPROVED";

    private final ObjectMapper objectMapper;

    /** Returns the reviewers who APPROVED, in document order. */
    List<Approver> approvers(String reviewDecisionsJson) {
        if (reviewDecisionsJson == null || reviewDecisionsJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(reviewDecisionsJson);
            if (root == null || !root.isArray()) {
                return List.of();
            }
            var approvers = new ArrayList<Approver>();
            for (JsonNode decision : root) {
                var decisionType = text(decision, "decision");
                if (!DECISION_APPROVED.equalsIgnoreCase(decisionType)) {
                    continue;
                }
                JsonNode reviewer = decision.get("reviewer");
                approvers.add(new Approver(
                        reviewer == null ? null : text(reviewer, "email"),
                        reviewer == null ? null : text(reviewer, "displayName"),
                        decisionType,
                        parseInstant(text(decision, "decidedAt"))));
            }
            return List.copyOf(approvers);
        } catch (RuntimeException ex) {
            log.debug("Could not parse review_decisions JSON for compliance report; skipping approvers", ex);
            return List.of();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
