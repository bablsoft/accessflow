package com.bablsoft.accessflow.apigov.events;

import java.util.UUID;

/** A reminder is due for a governed API request still awaiting review (#622). */
public record ApiReviewNudgedEvent(UUID apiRequestId) {
}
