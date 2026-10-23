package com.bablsoft.accessflow.deploygov.api;

/** The metrics window is empty, inverted, or longer than the 366-day cap (#984). */
public class InvalidDeploymentMetricsPeriodException extends DeploymentGovernanceException {

    public InvalidDeploymentMetricsPeriodException() {
        super("The metrics window must satisfy from < to and span at most 366 days");
    }
}
