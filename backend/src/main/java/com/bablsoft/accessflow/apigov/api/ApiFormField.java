package com.bablsoft.accessflow.apigov.api;

/**
 * One part of a {@code FORM_DATA} or {@code FORM_URLENCODED} request body. A {@code TEXT} part carries
 * its literal value; a {@code FILE} part (form-data only) carries a base64-encoded file in
 * {@code value} together with its {@code filename} and {@code contentType}. {@code FORM_URLENCODED}
 * bodies use only {@code TEXT} parts.
 */
public record ApiFormField(String key, ApiFormFieldType type, String value, String filename,
                           String contentType) {

    public enum ApiFormFieldType {
        TEXT,
        FILE
    }
}
