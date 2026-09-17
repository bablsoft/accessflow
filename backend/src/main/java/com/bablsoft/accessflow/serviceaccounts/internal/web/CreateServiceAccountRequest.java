package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.serviceaccounts.api.CreateServiceAccountCommand;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * {@code roleId} wins over the legacy {@code role} enum; both absent means {@code READONLY}.
 * {@code mcpToolAllowList} is {@code null} for every tool and {@code []} for none; the entries are
 * checked against the tool catalog by the service (422), blank entries here (400).
 */
public record CreateServiceAccountRequest(
        @NotBlank(message = "{validation.service_account_email.required}")
        @Email(message = "{validation.service_account_email.invalid}")
        @Size(max = 255, message = "{validation.service_account_email.size}")
        String email,

        @NotBlank(message = "{validation.service_account_display_name.required}")
        @Size(max = 255, message = "{validation.service_account_display_name.size}")
        String displayName,

        UserRoleType role,
        UUID roleId,

        @Size(max = 500, message = "{validation.service_account_description.size}")
        String description,

        UUID ownerUserId,

        List<@NotBlank(message = "{validation.service_account_tool.blank}") String> mcpToolAllowList,

        @Positive(message = "{validation.service_account_rate_limit.positive}")
        Integer rateLimitPerMinute,

        @Positive(message = "{validation.service_account_rate_limit.positive}")
        Integer rateLimitPerDay
) {
    public CreateServiceAccountCommand toCommand() {
        return new CreateServiceAccountCommand(email, displayName, role, roleId, description, ownerUserId,
                mcpToolAllowList, rateLimitPerMinute, rateLimitPerDay);
    }
}
