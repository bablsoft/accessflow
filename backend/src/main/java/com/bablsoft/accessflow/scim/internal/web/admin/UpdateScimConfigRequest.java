package com.bablsoft.accessflow.scim.internal.web.admin;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.scim.api.UpdateScimConfigCommand;
import jakarta.validation.constraints.Pattern;

record UpdateScimConfigRequest(
        Boolean enabled,
        @Pattern(regexp = "userName|emails\\.primary",
                message = "{validation.scim.attr_email}")
        String attrEmail,
        @Pattern(regexp = "displayName|name\\.formatted|userName",
                message = "{validation.scim.attr_display_name}")
        String attrDisplayName,
        UserRoleType defaultRole) {

    UpdateScimConfigCommand toCommand() {
        return new UpdateScimConfigCommand(enabled, attrEmail, attrDisplayName, defaultRole);
    }
}
