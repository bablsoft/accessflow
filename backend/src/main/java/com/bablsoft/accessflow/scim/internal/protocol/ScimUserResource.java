package com.bablsoft.accessflow.scim.internal.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * The SCIM User resource, pragmatic subset (#621). Deliberately has no password-shaped field:
 * AccessFlow never accepts or emits credentials over SCIM — unknown request fields (including
 * {@code password}, which Okta may send) are ignored on read and can never be echoed back.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record ScimUserResource(
        List<String> schemas,
        String id,
        String externalId,
        String userName,
        String displayName,
        ScimName name,
        List<ScimEmail> emails,
        Boolean active,
        ScimMeta meta
) {
}
