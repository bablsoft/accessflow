package com.bablsoft.accessflow.scheduling.api;

/** The job is neither registered in this process nor present in the execution history. HTTP 404. */
public final class JobNotFoundException extends RuntimeException {

    private final String jobName;

    public JobNotFoundException(String jobName) {
        super("Scheduled job not found: " + jobName);
        this.jobName = jobName;
    }

    public String jobName() {
        return jobName;
    }
}
