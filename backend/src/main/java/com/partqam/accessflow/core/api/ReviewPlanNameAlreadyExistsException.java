package com.partqam.accessflow.core.api;

public final class ReviewPlanNameAlreadyExistsException extends ReviewPlanAdminException {

    private final String name;

    public ReviewPlanNameAlreadyExistsException(String name) {
        super("A review plan with name '" + name + "' already exists in this organization");
        this.name = name;
    }

    public String name() {
        return name;
    }
}
