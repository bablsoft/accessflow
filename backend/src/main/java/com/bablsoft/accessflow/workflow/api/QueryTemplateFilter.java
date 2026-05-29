package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * Optional list-endpoint filters for query templates. All fields are nullable: {@code null} means
 * "no filter on this dimension". Visibility enforcement (org + owner-or-TEAM) is layered on top of
 * these filters by {@link QueryTemplateService#list}.
 */
public record QueryTemplateFilter(
        UUID datasourceId,
        String tag,
        QueryTemplateVisibility visibility,
        String search
) {}
