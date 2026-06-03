package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.RowSecurityValueType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateRowSecurityPolicyRequest(
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
        Boolean enabled
) {}
