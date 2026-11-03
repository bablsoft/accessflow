package com.bablsoft.accessflow.apigov.internal.web;

/** {@code against} is neither {@code previous} nor a UUID (#887); mapped to 400. */
class InvalidDiffAgainstException extends RuntimeException {

    InvalidDiffAgainstException(String against) {
        super("Invalid against value: " + against);
    }
}
