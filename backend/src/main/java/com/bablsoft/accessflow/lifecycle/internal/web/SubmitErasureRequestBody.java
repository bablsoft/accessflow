package com.bablsoft.accessflow.lifecycle.internal.web;

import com.bablsoft.accessflow.lifecycle.api.LifecycleSubjectType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Self-service request to erase a data subject's records in a datasource. */
public record SubmitErasureRequestBody(
        @NotNull(message = "{validation.lifecycle.datasource.required}")
        UUID datasourceId,

        @NotNull(message = "{validation.lifecycle.subject_type.required}")
        LifecycleSubjectType subjectType,

        @NotBlank(message = "{validation.lifecycle.subject_identifier.required}")
        @Size(max = 255, message = "{validation.lifecycle.subject_identifier.size}")
        String subjectIdentifier,

        @Size(max = 2000, message = "{validation.lifecycle.reason.size}")
        String reason) {
}
