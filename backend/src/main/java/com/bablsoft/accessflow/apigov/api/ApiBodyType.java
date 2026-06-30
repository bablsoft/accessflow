package com.bablsoft.accessflow.apigov.api;

/**
 * How a governed API request carries its body, mirroring a Postman request: {@code NONE} (no body),
 * {@code RAW} (a single text payload with an explicit content type), {@code FORM_DATA}
 * (multipart/form-data — text and file parts), {@code FORM_URLENCODED}
 * (application/x-www-form-urlencoded key/value pairs), and {@code BINARY} (a single file body carried
 * as bounded base64 inline).
 */
public enum ApiBodyType {
    NONE,
    RAW,
    FORM_DATA,
    FORM_URLENCODED,
    BINARY
}
