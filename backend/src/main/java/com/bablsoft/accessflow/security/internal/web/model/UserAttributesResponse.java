package com.bablsoft.accessflow.security.internal.web.model;

import java.util.Map;

public record UserAttributesResponse(Map<String, String> attributes) {

    public UserAttributesResponse {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
