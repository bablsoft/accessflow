package com.bablsoft.accessflow.scim.internal.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record ScimMeta(String resourceType, Instant created, Instant lastModified,
                       String location) {
}
