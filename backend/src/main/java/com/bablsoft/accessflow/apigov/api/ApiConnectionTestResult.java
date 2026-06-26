package com.bablsoft.accessflow.apigov.api;

/** Outcome of a connector "test connection" probe. */
public record ApiConnectionTestResult(boolean success, String message) {
}
