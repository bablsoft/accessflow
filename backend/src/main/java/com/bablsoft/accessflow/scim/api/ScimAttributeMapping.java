package com.bablsoft.accessflow.scim.api;

import java.util.Set;

/** Allowed values for the per-org SCIM attribute mapping (#621). */
public final class ScimAttributeMapping {

    /** SCIM attributes the user's email may be read from. */
    public static final Set<String> EMAIL_SOURCES = Set.of("userName", "emails.primary");

    /** SCIM attributes the user's display name may be read from. */
    public static final Set<String> DISPLAY_NAME_SOURCES =
            Set.of("displayName", "name.formatted", "userName");

    public static final String DEFAULT_EMAIL_SOURCE = "userName";
    public static final String DEFAULT_DISPLAY_NAME_SOURCE = "displayName";

    private ScimAttributeMapping() {
    }
}
