package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountClearableField;
import com.bablsoft.accessflow.serviceaccounts.api.UpdateServiceAccountCommand;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Every field is null-means-unchanged. UI-owned fields are reset by naming them in {@code clear}
 * — {@code MCP_TOOL_ALLOW_LIST} there re-opens every tool — and a field may not be both sent and
 * cleared in the same request.
 */
public record UpdateServiceAccountRequest(
        @Size(max = 255, message = "{validation.service_account_display_name.size}")
        @Pattern(regexp = "\\s*\\S[\\s\\S]*", message = "{validation.service_account_display_name.required}")
        String displayName,

        UserRoleType role,
        UUID roleId,
        Boolean active,

        @Size(max = 500, message = "{validation.service_account_description.size}")
        String description,

        UUID ownerUserId,

        List<@NotBlank(message = "{validation.service_account_tool.blank}") String> mcpToolAllowList,

        @Positive(message = "{validation.service_account_rate_limit.positive}")
        Integer rateLimitPerMinute,

        @Positive(message = "{validation.service_account_rate_limit.positive}")
        Integer rateLimitPerDay,

        Set<ServiceAccountClearableField> clear
) {
    @AssertTrue(message = "{validation.service_account_clear.conflict}")
    public boolean isClearDisjointFromValues() {
        var set = clear == null ? EnumSet.noneOf(ServiceAccountClearableField.class) : clear;
        return !(set.contains(ServiceAccountClearableField.DESCRIPTION) && description != null)
                && !(set.contains(ServiceAccountClearableField.OWNER_USER_ID) && ownerUserId != null)
                && !(set.contains(ServiceAccountClearableField.MCP_TOOL_ALLOW_LIST) && mcpToolAllowList != null)
                && !(set.contains(ServiceAccountClearableField.RATE_LIMIT_PER_MINUTE) && rateLimitPerMinute != null)
                && !(set.contains(ServiceAccountClearableField.RATE_LIMIT_PER_DAY) && rateLimitPerDay != null);
    }

    public UpdateServiceAccountCommand toCommand() {
        return new UpdateServiceAccountCommand(displayName, role, roleId, active, description, ownerUserId,
                mcpToolAllowList, rateLimitPerMinute, rateLimitPerDay, clear);
    }

    /** The field names present in the body — audit metadata, never the values. */
    public List<String> presentFields() {
        var fields = new ArrayList<String>();
        if (displayName != null) {
            fields.add("display_name");
        }
        if (role != null) {
            fields.add("role");
        }
        if (roleId != null) {
            fields.add("role_id");
        }
        if (active != null) {
            fields.add("active");
        }
        if (description != null) {
            fields.add("description");
        }
        if (ownerUserId != null) {
            fields.add("owner_user_id");
        }
        if (mcpToolAllowList != null) {
            fields.add("mcp_tool_allow_list");
        }
        if (rateLimitPerMinute != null) {
            fields.add("rate_limit_per_minute");
        }
        if (rateLimitPerDay != null) {
            fields.add("rate_limit_per_day");
        }
        return List.copyOf(fields);
    }

    /** The fields the body asks to reset — audit metadata. */
    public List<String> clearedFields() {
        if (clear == null) {
            return List.of();
        }
        return clear.stream().sorted().map(field -> field.name().toLowerCase()).toList();
    }
}
