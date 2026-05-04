package com.partqam.accessflow.core.api;

public record ConnectionTestResult(boolean ok, long latencyMs, String message) {}
