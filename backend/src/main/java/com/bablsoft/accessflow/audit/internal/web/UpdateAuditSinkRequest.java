package com.bablsoft.accessflow.audit.internal.web;

import jakarta.validation.constraints.Size;

import java.util.Map;

/** Partial update: omitted fields are left unchanged; the sink type is immutable. */
record UpdateAuditSinkRequest(
        @Size(max = 255, message = "{validation.audit_sink_name.size}") String name,
        Map<String, Object> config,
        Boolean enabled) {
}
