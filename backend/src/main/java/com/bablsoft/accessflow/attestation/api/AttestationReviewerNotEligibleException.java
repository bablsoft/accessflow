package com.bablsoft.accessflow.attestation.api;

import java.util.UUID;

/**
 * Thrown when the caller may not act on an item — they are not an eligible reviewer for the item's
 * datasource, or they are the subject of the grant (self-review is unconditionally blocked).
 */
public class AttestationReviewerNotEligibleException extends AttestationException {

    public AttestationReviewerNotEligibleException(UUID userId, UUID itemId) {
        super("User " + userId + " is not eligible to attest item " + itemId);
    }
}
