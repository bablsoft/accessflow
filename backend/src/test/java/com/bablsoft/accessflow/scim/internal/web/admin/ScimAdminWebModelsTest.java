package com.bablsoft.accessflow.scim.internal.web.admin;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.scim.api.IssuedScimToken;
import com.bablsoft.accessflow.scim.api.ScimConfigView;
import com.bablsoft.accessflow.scim.api.ScimTokenView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScimAdminWebModelsTest {

    @Test
    void scimConfigResponseMapsAllFields() {
        var view = new ScimConfigView(UUID.randomUUID(), UUID.randomUUID(), true,
                "emails.primary", "name.formatted", UserRoleType.READONLY,
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-02T00:00:00Z"));

        var response = ScimConfigResponse.from(view);

        assertThat(response.id()).isEqualTo(view.id());
        assertThat(response.enabled()).isTrue();
        assertThat(response.attrEmail()).isEqualTo("emails.primary");
        assertThat(response.attrDisplayName()).isEqualTo("name.formatted");
        assertThat(response.defaultRole()).isEqualTo(UserRoleType.READONLY);
    }

    @Test
    void updateRequestBuildsCommand() {
        var request = new UpdateScimConfigRequest(true, "userName", "displayName",
                UserRoleType.ANALYST);

        var command = request.toCommand();

        assertThat(command.enabled()).isTrue();
        assertThat(command.attrEmail()).isEqualTo("userName");
        assertThat(command.attrDisplayName()).isEqualTo("displayName");
        assertThat(command.defaultRole()).isEqualTo(UserRoleType.ANALYST);
    }

    @Test
    void createdTokenResponseCarriesRawTokenOnce() {
        var view = new ScimTokenView(UUID.randomUUID(), "okta", "af_scim_AbCd",
                Instant.now(), null, null);

        var response = CreatedScimTokenResponse.from(new IssuedScimToken(view, "af_scim_raw"));

        assertThat(response.rawToken()).isEqualTo("af_scim_raw");
        assertThat(response.token().tokenPrefix()).isEqualTo("af_scim_AbCd");
        assertThat(response.token().revokedAt()).isNull();
    }
}
