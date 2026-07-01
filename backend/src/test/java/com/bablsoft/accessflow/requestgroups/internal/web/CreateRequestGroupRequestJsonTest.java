package com.bablsoft.accessflow.requestgroups.internal.web;

import com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds real POST JSON through Jackson to guard #528: an omitted primitive-boolean field must
 * default to {@code false} rather than blowing up with {@code MismatchedInputException}
 * (Jackson 3 enables {@code FAIL_ON_NULL_FOR_PRIMITIVES} by default). The controller unit test
 * constructs the records directly and never exercises the deserializer, so it cannot catch this.
 */
class CreateRequestGroupRequestJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void bindsAndDefaultsOmittedPrimitiveBooleansToFalse() {
        var json = """
                {
                  "name": "bundle",
                  "description": "desc",
                  "items": [
                    { "targetKind": "QUERY", "datasourceId": "%s", "sqlText": "SELECT 1" },
                    {
                      "targetKind": "API_CALL",
                      "apiConnectorId": "%s",
                      "verb": "POST",
                      "requestPath": "/x",
                      "bodyType": "FORM_DATA",
                      "formFields": [ { "name": "f", "value": "v" } ]
                    }
                  ]
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        var request = objectMapper.readValue(json, CreateRequestGroupRequest.class);
        var command = request.toCommand(UUID.randomUUID(), UUID.randomUUID(), false);

        assertThat(command.continueOnError()).isFalse();
        assertThat(command.items()).hasSize(2);
        assertThat(command.items().get(0).targetKind()).isEqualTo(RequestGroupTargetKind.QUERY);
        assertThat(command.items().get(1).targetKind()).isEqualTo(RequestGroupTargetKind.API_CALL);
        assertThat(command.items().get(1).transactional()).isFalse();
        assertThat(command.items().get(1).formFields()).hasSize(1);
        assertThat(command.items().get(1).formFields().get(0).file()).isFalse();
    }

    @Test
    void bindsExplicitPrimitiveBooleansWhenPresent() {
        var json = """
                {
                  "name": "bundle",
                  "continueOnError": true,
                  "items": [
                    {
                      "targetKind": "QUERY",
                      "datasourceId": "%s",
                      "sqlText": "SELECT 1",
                      "transactional": true
                    },
                    {
                      "targetKind": "API_CALL",
                      "apiConnectorId": "%s",
                      "verb": "POST",
                      "requestPath": "/x",
                      "bodyType": "FORM_DATA",
                      "formFields": [ { "name": "f", "value": "v", "file": true } ]
                    }
                  ]
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        var request = objectMapper.readValue(json, CreateRequestGroupRequest.class);
        var command = request.toCommand(UUID.randomUUID(), UUID.randomUUID(), false);

        assertThat(command.continueOnError()).isTrue();
        assertThat(command.items().get(0).transactional()).isTrue();
        assertThat(command.items().get(1).formFields().get(0).file()).isTrue();
    }
}
