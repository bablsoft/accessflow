package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.UserRoleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

public record UpdateUserRequest(
        UserRoleType role,
        UUID roleId,
        Boolean active,
        @Size(max = 255, message = "{validation.display_name.max}") String displayName,
        @Size(max = 50, message = "{validation.user_attributes.max}")
        Map<@NotBlank(message = "{validation.user_attribute_key.blank}")
            @Size(max = 128, message = "{validation.user_attribute_key.size}") String,
            @NotNull(message = "{validation.user_attribute_value.required}")
            @Size(max = 512, message = "{validation.user_attribute_value.size}") String> attributes
) {}
