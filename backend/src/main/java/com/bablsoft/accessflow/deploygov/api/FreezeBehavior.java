package com.bablsoft.accessflow.deploygov.api;

/**
 * How an active deployment freeze window treats a deployment. {@code HOLD} keeps an approved
 * request not-releasable until the window closes; {@code REJECT} auto-rejects at submission.
 */
public enum FreezeBehavior {
    HOLD,
    REJECT
}
