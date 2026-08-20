package com.bablsoft.accessflow.audit.internal.web;

import com.bablsoft.accessflow.audit.api.AuditSinkType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

record CreateAuditSinkRequest(
        @NotBlank(message = "{validation.audit_sink_name.required}")
        @Size(max = 255, message = "{validation.audit_sink_name.size}")
        String name,
        @NotNull(message = "{validation.audit_sink_type.required}") AuditSinkType type,
        @NotNull(message = "{validation.audit_sink_config.required}") Map<String, Object> config) {
}
