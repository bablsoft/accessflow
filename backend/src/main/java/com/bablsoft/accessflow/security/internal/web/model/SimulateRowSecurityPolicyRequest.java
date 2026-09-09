package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.RowSecurityPolicyDraft;
import com.bablsoft.accessflow.core.api.RowSecurityValueType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Dry-run request for a draft row-security policy (issue AF-630): the create body, plus the window
 * to replay and an optional {@code replacesPolicyId} for the "edit an existing policy" case.
 */
public record SimulateRowSecurityPolicyRequest(
        @NotNull(message = "{validation.simulation_period.required}") Instant from,
        @NotNull(message = "{validation.simulation_period.required}") Instant to,
        @NotNull(message = "{validation.simulation_draft.required}") @Valid Draft draft) {

    /** The unsaved policy, carrying every constraint its persisted counterpart carries. */
    public record Draft(
            UUID replacesPolicyId,
            @NotBlank(message = "{validation.row_security_table.required}")
            @Size(max = 512, message = "{validation.row_security_table.size}")
            String tableName,
            @NotBlank(message = "{validation.row_security_column.required}")
            @Size(max = 512, message = "{validation.row_security_column.size}")
            String columnName,
            @NotNull(message = "{validation.row_security_operator.required}")
            RowSecurityOperator operator,
            @NotNull(message = "{validation.row_security_value_type.required}")
            RowSecurityValueType valueType,
            @NotBlank(message = "{validation.row_security_value.required}")
            @Size(max = 512, message = "{validation.row_security_value.size}")
            String valueExpression,
            List<String> appliesToRoles,
            List<UUID> appliesToGroupIds,
            List<UUID> appliesToUserIds,
            Boolean enabled) {

        public RowSecurityPolicyDraft toCommand() {
            return new RowSecurityPolicyDraft(replacesPolicyId, tableName, columnName, operator,
                    valueType, valueExpression, appliesToRoles, appliesToGroupIds, appliesToUserIds,
                    enabled == null || enabled);
        }
    }
}
