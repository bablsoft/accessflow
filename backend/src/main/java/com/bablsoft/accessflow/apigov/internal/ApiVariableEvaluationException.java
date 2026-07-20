package com.bablsoft.accessflow.apigov.internal;

import java.io.Serial;
import java.util.Arrays;
import java.util.List;

/**
 * A connector variable could not be evaluated (AF-613). Carries an i18n message key and its
 * arguments rather than a rendered string, so the same failure can surface as a save-time 422 or an
 * execution-time error in the caller's locale.
 *
 * <p>Arguments must only ever be variable names, algorithm names or numeric limits — never an
 * expression, a resolved value, or a secret.
 */
class ApiVariableEvaluationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient List<Object> args;
    private final String messageKey;

    ApiVariableEvaluationException(String messageKey, Object... args) {
        super(messageKey);
        this.messageKey = messageKey;
        this.args = List.copyOf(Arrays.asList(args));
    }

    String messageKey() {
        return messageKey;
    }

    Object[] args() {
        return args.toArray();
    }
}
