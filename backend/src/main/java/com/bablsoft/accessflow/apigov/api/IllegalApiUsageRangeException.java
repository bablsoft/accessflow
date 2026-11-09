package com.bablsoft.accessflow.apigov.api;

/** A usage read asked for an empty, inverted or over-long date range (#973). */
public class IllegalApiUsageRangeException extends ApiGovException {

    private final int maxDays;

    public IllegalApiUsageRangeException(String message, int maxDays) {
        super(message);
        this.maxDays = maxDays;
    }

    public int maxDays() {
        return maxDays;
    }
}
