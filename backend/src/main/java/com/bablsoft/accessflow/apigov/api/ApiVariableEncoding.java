package com.bablsoft.accessflow.apigov.api;

/**
 * How a connector variable's raw bytes are rendered as text (AF-613).
 */
public enum ApiVariableEncoding {

    /** Lowercase hexadecimal. */
    HEX,

    /** Standard base64, padded. */
    BASE64,

    /** URL-safe base64, <em>unpadded</em> (the RFC 7515 shape most vendors expect). */
    BASE64URL
}
