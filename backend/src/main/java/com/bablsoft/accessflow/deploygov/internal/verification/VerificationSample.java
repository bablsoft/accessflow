package com.bablsoft.accessflow.deploygov.internal.verification;

import com.bablsoft.accessflow.deploygov.api.VerificationCheckKind;

/**
 * One evaluated sample of one verification check (#1024). {@code observedValue} is the HTTP status
 * or the PromQL value seen; {@code detail} is an English diagnostic of why it failed, never a
 * response body.
 */
public record VerificationSample(String checkName, VerificationCheckKind kind, boolean passed,
                                 String observedValue, String detail) {
}
