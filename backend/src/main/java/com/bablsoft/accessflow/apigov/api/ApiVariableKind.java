package com.bablsoft.accessflow.apigov.api;

/**
 * How a connector variable's value is produced (AF-613). Every kind first renders its
 * {@code expression} against the in-flight request and the already-resolved variables; the rendered
 * string is then the kind's input.
 */
public enum ApiVariableKind {

    /** The rendered expression, verbatim. Encoding is ignored — use {@link #ENCODE} to re-encode. */
    CONSTANT,

    /** A fresh random UUID per evaluation. Expression must be blank. */
    UUID,

    /**
     * The current instant truncated to seconds. A blank expression yields ISO-8601; otherwise the
     * expression is a {@code DateTimeFormatter} pattern applied at UTC.
     */
    TIMESTAMP,

    /** The current epoch millisecond count. Expression must be blank. */
    EPOCH_MILLIS,

    /** Cryptographically random bytes. Expression is an optional byte count (default 16, 1..256). */
    RANDOM_HEX,

    /** A digest of the rendered expression. Requires SHA256 or MD5. */
    HASH,

    /** A keyed MAC of the rendered expression. Requires an HMAC algorithm and a stored secret. */
    HMAC,

    /** Re-encodes the rendered expression's UTF-8 bytes. Requires an explicit encoding. */
    ENCODE
}
