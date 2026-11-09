package com.bablsoft.accessflow.apigov.api;

/** Which connector rate limit refused a call (#972). */
public enum ApiConnectorLimitKind {
    MINUTE,
    DAY,
    USER_MINUTE,
    USER_DAY
}
