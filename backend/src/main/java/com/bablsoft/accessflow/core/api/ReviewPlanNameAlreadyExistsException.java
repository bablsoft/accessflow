package com.bablsoft.accessflow.core.api;

public final class ReviewPlanNameAlreadyExistsException extends ReviewPlanAdminException {

    public ReviewPlanNameAlreadyExistsException(String name) {
        super("A review plan with name '" + name + "' already exists in this organization");
    }
}
