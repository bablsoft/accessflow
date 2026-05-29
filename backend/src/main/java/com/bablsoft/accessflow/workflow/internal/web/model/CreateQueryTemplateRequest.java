package com.bablsoft.accessflow.workflow.internal.web.model;

import com.bablsoft.accessflow.workflow.api.QueryTemplateVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateQueryTemplateRequest(
        @NotBlank(message = "{validation.query_template_name.required}")
        @Size(max = 128, message = "{validation.query_template_name.size}")
        String name,

        @NotBlank(message = "{validation.query_template_body.required}")
        @Size(max = 100_000, message = "{validation.query_template_body.max}")
        String body,

        @Size(max = 1000, message = "{validation.query_template_description.max}")
        String description,

        @Size(max = 10, message = "{validation.query_template_tags.max}")
        List<@Size(max = 32, message = "{validation.query_template_tag.size}") String> tags,

        UUID datasourceId,

        @NotNull(message = "{validation.query_template_visibility.required}")
        QueryTemplateVisibility visibility
) {}
