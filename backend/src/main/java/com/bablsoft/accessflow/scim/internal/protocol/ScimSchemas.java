package com.bablsoft.accessflow.scim.internal.protocol;

/** SCIM 2.0 schema URNs (RFC 7643/7644) used by the pragmatic subset (#621). */
public final class ScimSchemas {

    public static final String USER = "urn:ietf:params:scim:schemas:core:2.0:User";
    public static final String GROUP = "urn:ietf:params:scim:schemas:core:2.0:Group";
    public static final String LIST_RESPONSE = "urn:ietf:params:scim:api:messages:2.0:ListResponse";
    public static final String PATCH_OP = "urn:ietf:params:scim:api:messages:2.0:PatchOp";
    public static final String ERROR = "urn:ietf:params:scim:api:messages:2.0:Error";
    public static final String SERVICE_PROVIDER_CONFIG =
            "urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig";
    public static final String RESOURCE_TYPE = "urn:ietf:params:scim:schemas:core:2.0:ResourceType";
    public static final String SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:Schema";

    private ScimSchemas() {
    }
}
