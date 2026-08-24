package com.bablsoft.accessflow.deploygov.api;

/**
 * The submitter tried to decide their own deployment request (#692). Never allowed, regardless of
 * role — the API key's owning user is the submitter (CLAUDE.md security rule 5).
 */
public class DeploymentSelfApprovalException extends DeploymentGovernanceException {

    public DeploymentSelfApprovalException() {
        super("A submitter cannot decide their own deployment request");
    }
}
