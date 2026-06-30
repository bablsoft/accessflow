package com.bablsoft.accessflow.requestgroups.internal.web;

import java.time.Instant;

/** Submit-time options: optional break-glass + optional deferred run timestamp. */
record SubmitRequestGroupRequest(boolean breakGlass, Instant scheduledFor) {
}
