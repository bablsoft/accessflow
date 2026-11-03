package com.bablsoft.accessflow.apigov.api;

/** A response is neither JSON nor XML, or does not parse (#887). */
public class ApiResponseDiffUnsupportedException extends ApiGovException {

    private final String contentType;

    public ApiResponseDiffUnsupportedException(String contentType) {
        super("Response cannot be diffed: " + contentType);
        this.contentType = contentType;
    }

    public String contentType() {
        return contentType;
    }
}
