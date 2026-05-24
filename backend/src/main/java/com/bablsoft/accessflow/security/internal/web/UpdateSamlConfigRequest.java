package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.UpdateSamlConfigCommand;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record UpdateSamlConfigRequest(
        @Size(max = 1024, message = "{validation.saml_config.text.max}") String idpMetadataUrl,
        @Size(max = 1024, message = "{validation.saml_config.text.max}") String idpEntityId,
        @Size(max = 1024, message = "{validation.saml_config.text.max}") String spEntityId,
        @Size(max = 1024, message = "{validation.saml_config.text.max}") String acsUrl,
        @Size(max = 1024, message = "{validation.saml_config.text.max}") String sloUrl,
        // Accepts: empty string (clear the cert), the masked sentinel "********"
        // (preserve-existing path the frontend uses when the user doesn't edit
        // the cert), or a PEM-encoded X.509 certificate block. Null is allowed
        // by @Pattern, leaving the field "omitted" in the partial-update path.
        @Pattern(
                regexp = "^$|^\\*{8}$|^-----BEGIN CERTIFICATE-----[\\s\\S]+-----END CERTIFICATE-----\\s*$",
                message = "{validation.saml_config.signing_cert.invalid}")
        String signingCertPem,
        @Size(max = 255, message = "{validation.saml_config.text.max}") String attrEmail,
        @Size(max = 255, message = "{validation.saml_config.text.max}") String attrDisplayName,
        @Size(max = 255, message = "{validation.saml_config.text.max}") String attrRole,
        UserRoleType defaultRole,
        Boolean active) {

    UpdateSamlConfigCommand toCommand() {
        return new UpdateSamlConfigCommand(
                idpMetadataUrl,
                idpEntityId,
                spEntityId,
                acsUrl,
                sloUrl,
                signingCertPem,
                attrEmail,
                attrDisplayName,
                attrRole,
                defaultRole,
                active);
    }
}
