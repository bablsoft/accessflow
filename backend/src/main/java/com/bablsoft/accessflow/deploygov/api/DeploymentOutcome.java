package com.bablsoft.accessflow.deploygov.api;

/**
 * Post-deploy result the pipeline reports after the gate opened: {@code SUCCEEDED} /
 * {@code FAILED} for the deployment itself, {@code ROLLED_BACK} when a completed deployment was
 * later reverted. Distinct from the request status — {@code EXECUTED} means the gate opened and
 * the pipeline confirmed it proceeded; the outcome records what happened afterwards.
 */
public enum DeploymentOutcome {
    SUCCEEDED,
    FAILED,
    ROLLED_BACK
}
