package com.bablsoft.accessflow.scim.internal.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** The SCIM error envelope — used instead of RFC 9457 ProblemDetail on {@code /scim/v2/**}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record ScimError(List<String> schemas, String status, String scimType, String detail) {

    public static ScimError of(int status, String scimType, String detail) {
        return new ScimError(List.of(ScimSchemas.ERROR), String.valueOf(status), scimType, detail);
    }
}
