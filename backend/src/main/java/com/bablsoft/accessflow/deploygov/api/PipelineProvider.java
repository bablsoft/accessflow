package com.bablsoft.accessflow.deploygov.api;

/**
 * CI/CD provider a deployment pipeline runs on. {@code GITHUB_ACTIONS} / {@code GITLAB_CI} /
 * {@code AZURE_PIPELINES} / {@code JENKINS} / {@code CIRCLECI} / {@code BITBUCKET_PIPELINES} match
 * the native wrappers shipped for those systems; {@code GENERIC} covers any other pipeline that
 * calls the trigger/gate REST API directly.
 */
public enum PipelineProvider {
    GITHUB_ACTIONS,
    GITLAB_CI,
    AZURE_PIPELINES,
    JENKINS,
    CIRCLECI,
    BITBUCKET_PIPELINES,
    GENERIC
}
