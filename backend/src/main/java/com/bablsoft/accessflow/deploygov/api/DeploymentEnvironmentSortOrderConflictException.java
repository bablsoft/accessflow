package com.bablsoft.accessflow.deploygov.api;

/**
 * Another environment of the pipeline already holds the requested {@code sortOrder} (#877). The
 * ladder position is unique per pipeline so a "lower-ordered environments" gate can never evaluate
 * an ambiguous set.
 */
public class DeploymentEnvironmentSortOrderConflictException extends DeploymentGovernanceException {

    private final int sortOrder;

    public DeploymentEnvironmentSortOrderConflictException(int sortOrder) {
        super("Deployment environment sort order already in use: " + sortOrder);
        this.sortOrder = sortOrder;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
