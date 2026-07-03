package com.bablsoft.accessflow.apigov.internal.web;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateApiConnectorGroupPermissionRequestTest {

    @Test
    void toCommandMapsAllFields() {
        var expiresAt = Instant.parse("2030-01-01T00:00:00Z");
        var request = new UpdateApiConnectorGroupPermissionRequest(true, false, true, expiresAt,
                List.of("createPet"), List.of("data.token"));

        var command = request.toCommand();

        assertThat(command.canRead()).isTrue();
        assertThat(command.canWrite()).isFalse();
        assertThat(command.canBreakGlass()).isTrue();
        assertThat(command.expiresAt()).isEqualTo(expiresAt);
        assertThat(command.allowedOperations()).containsExactly("createPet");
        assertThat(command.restrictedResponseFields()).containsExactly("data.token");
    }

    @Test
    void toCommandPreservesNulls() {
        var command = new UpdateApiConnectorGroupPermissionRequest(false, false, false, null, null, null)
                .toCommand();

        assertThat(command.expiresAt()).isNull();
        assertThat(command.allowedOperations()).isNull();
        assertThat(command.restrictedResponseFields()).isNull();
    }
}
