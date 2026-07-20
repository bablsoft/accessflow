package com.bablsoft.accessflow.apigov.internal.web;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateApiConnectorPermissionRequestTest {

    @Test
    void toCommandMapsAllFields() {
        var expiresAt = Instant.parse("2030-01-01T00:00:00Z");
        var request = new UpdateApiConnectorPermissionRequest(true, false, true, false, expiresAt,
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
        var command = new UpdateApiConnectorPermissionRequest(false, false, false, false, null, null, null)
                .toCommand();

        assertThat(command.expiresAt()).isNull();
        assertThat(command.allowedOperations()).isNull();
        assertThat(command.restrictedResponseFields()).isNull();
    }

    /**
     * AF-613 added {@code canOverrideVariables}. It is boxed precisely so a client written before
     * that field existed still works — Jackson 3 rejects an <em>absent</em> primitive boolean with a
     * 500 rather than defaulting it, which would have broken every existing caller.
     */
    @Test
    void toCommandDefaultsAnOmittedOverrideFlagToFalse() {
        var command = new UpdateApiConnectorPermissionRequest(true, false, true, null, null, null, null).toCommand();

        assertThat(command.canOverrideVariables()).isFalse();
    }

    @Test
    void toCommandCarriesTheOverrideFlagWhenSet() {
        var command = new UpdateApiConnectorPermissionRequest(true, false, true, true, null, null, null).toCommand();

        assertThat(command.canOverrideVariables()).isTrue();
    }
}
