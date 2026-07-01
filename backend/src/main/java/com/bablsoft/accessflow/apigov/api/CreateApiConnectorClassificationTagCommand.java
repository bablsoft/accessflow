package com.bablsoft.accessflow.apigov.api;

import com.bablsoft.accessflow.core.api.DataClassification;

import java.util.List;

/**
 * Command to tag an API-connector response field with one or more data classifications (AF-518). One
 * command yields one persisted tag per entry in {@code classifications}.
 *
 * @param matcherType     how {@code fieldRef} targets the field (required)
 * @param operationId     the schema operation being tagged; required for {@code SCHEMA_FIELD},
 *                        {@code null}/blank tags the connector itself for path/regex matchers
 * @param fieldRef        the schema field / JSON path / XPath / regex (required)
 * @param classifications the classifications to apply (at least one)
 * @param note            optional free-text note
 * @param applyMasking    when {@code null} or {@code true}, a tag whose classification has a masking
 *                        default auto-creates a connector masking policy (idempotent)
 */
public record CreateApiConnectorClassificationTagCommand(
        ApiMaskingMatcherType matcherType,
        String operationId,
        String fieldRef,
        List<DataClassification> classifications,
        String note,
        Boolean applyMasking) {

    public CreateApiConnectorClassificationTagCommand {
        classifications = classifications == null ? List.of() : List.copyOf(classifications);
    }
}
