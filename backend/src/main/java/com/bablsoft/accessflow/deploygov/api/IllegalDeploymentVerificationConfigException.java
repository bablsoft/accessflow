package com.bablsoft.accessflow.deploygov.api;

/**
 * An environment's verification config breaks a semantic rule, or one of its URLs is refused by
 * the outbound target policy (#1024). {@code reason} is a snake_case code; {@code checkName} names
 * the offending check, or is {@code null} for a config-level rule.
 */
public class IllegalDeploymentVerificationConfigException extends DeploymentGovernanceException {

    private final String reason;
    private final String checkName;

    public IllegalDeploymentVerificationConfigException(String reason, String checkName) {
        super(checkName == null ? "Invalid verification config: " + reason
                : "Invalid verification check '" + checkName + "': " + reason);
        this.reason = reason;
        this.checkName = checkName;
    }

    public String getReason() {
        return reason;
    }

    public String getCheckName() {
        return checkName;
    }
}
