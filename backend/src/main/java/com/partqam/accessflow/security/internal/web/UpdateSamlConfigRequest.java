package com.partqam.accessflow.security.internal.web;

import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.security.api.UpdateSamlConfigCommand;
import jakarta.validation.constraints.Size;

record UpdateSamlConfigRequest(
        @Size(max = 1024, message = "{validation.saml_config.text.max}") String idpMetadataUrl,
        @Size(max = 1024, message = "{validation.saml_config.text.max}") String idpEntityId,
        @Size(max = 1024, message = "{validation.saml_config.text.max}") String spEntityId,
        @Size(max = 1024, message = "{validation.saml_config.text.max}") String acsUrl,
        @Size(max = 1024, message = "{validation.saml_config.text.max}") String sloUrl,
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
