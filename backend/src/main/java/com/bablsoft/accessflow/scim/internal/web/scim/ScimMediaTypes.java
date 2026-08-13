package com.bablsoft.accessflow.scim.internal.web.scim;

import org.springframework.http.MediaType;

/** The SCIM media type (RFC 7644 §3.1). Requests and responses accept plain JSON too. */
public final class ScimMediaTypes {

    public static final String SCIM_JSON_VALUE = "application/scim+json";
    public static final MediaType SCIM_JSON = MediaType.parseMediaType(SCIM_JSON_VALUE);

    private ScimMediaTypes() {
    }
}
