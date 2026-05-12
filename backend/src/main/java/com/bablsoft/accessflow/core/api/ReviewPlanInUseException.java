package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public final class ReviewPlanInUseException extends ReviewPlanAdminException {

    public ReviewPlanInUseException(UUID id) {
        super("Review plan is in use and cannot be deleted: " + id);
    }
}
