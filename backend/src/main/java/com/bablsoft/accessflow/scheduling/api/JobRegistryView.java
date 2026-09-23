package com.bablsoft.accessflow.scheduling.api;

import java.time.Duration;
import java.util.List;

/**
 * The job registry of this process.
 *
 * @param schedulingEnabled {@code false} when {@code accessflow.scheduling.enabled=false}: the
 *                          registry is then legitimately empty, which is not the same as "no jobs"
 * @param recordingEnabled  {@code false} when execution recording is switched off
 * @param summaryWindow     the window the {@link JobHealthSummary} counts and mean cover
 */
public record JobRegistryView(
        boolean schedulingEnabled,
        boolean recordingEnabled,
        Duration summaryWindow,
        List<JobDescriptor> jobs) {

    public JobRegistryView {
        jobs = jobs == null ? List.of() : List.copyOf(jobs);
    }
}
