package com.bablsoft.accessflow.apigov.api;

/**
 * Digest / MAC algorithm for a connector variable (AF-613). The HMAC values are valid only for
 * {@link ApiVariableKind#HMAC} and the digest values only for {@link ApiVariableKind#HASH}; the
 * admin service rejects any other pairing at save time.
 *
 * <p>{@code MD5} is retained solely for vendor contracts that still mandate it. It is not
 * collision-resistant and must never be used for a signature.
 */
public enum ApiVariableAlgorithm {
    HMAC_SHA256,
    HMAC_SHA512,
    SHA256,
    MD5
}
