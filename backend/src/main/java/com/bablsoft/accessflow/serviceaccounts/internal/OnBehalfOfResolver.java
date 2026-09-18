package com.bablsoft.accessflow.serviceaccounts.internal;

import java.util.Optional;
import java.util.UUID;

/**
 * Turns the raw {@code X-AccessFlow-On-Behalf-Of} value into the user id of a human the calling
 * service account is allowed to act for (#874). Empty means "not permitted" for any reason —
 * unknown, another organization, inactive, not a human, no live grant — and the filter turns that
 * into one opaque 403, so the header cannot be used to probe which emails exist.
 */
public interface OnBehalfOfResolver {

    Optional<UUID> resolve(UUID serviceAccountUserId, UUID organizationId, String reference);
}
