package com.bablsoft.accessflow.core.api;

import java.util.UUID;

public final class ReviewPlanNotFoundException extends ReviewPlanAdminException {

    public ReviewPlanNotFoundException(UUID id) {
        super("Review plan not found: " + id);
    }
}
