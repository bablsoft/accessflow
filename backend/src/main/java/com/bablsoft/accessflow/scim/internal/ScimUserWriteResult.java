package com.bablsoft.accessflow.scim.internal;

import com.bablsoft.accessflow.scim.internal.protocol.ScimUserResource;

/**
 * Outcome of a SCIM user mutation (#621): {@code deactivated} is true only when this call flipped
 * the user active → inactive, so the controller can audit {@code SCIM_USER_DEACTIVATED} instead of
 * {@code SCIM_USER_UPDATED}.
 */
public record ScimUserWriteResult(ScimUserResource resource, boolean deactivated) {
}
