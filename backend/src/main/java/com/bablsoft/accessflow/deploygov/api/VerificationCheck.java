package com.bablsoft.accessflow.deploygov.api;

/** One post-deployment verification check of a {@link DeploymentVerificationConfig} (#1023). */
public sealed interface VerificationCheck permits HttpHealthCheck, PromqlThresholdCheck {

    String name();

    VerificationCheckKind kind();
}
