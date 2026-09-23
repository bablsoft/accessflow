package com.bablsoft.accessflow.scheduling.api;

/** How a registered job's cadence is expressed. */
public enum JobCadenceType {
    FIXED_DELAY,
    FIXED_RATE,
    CRON,
    ONE_TIME
}
