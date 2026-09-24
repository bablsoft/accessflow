package com.bablsoft.accessflow.security.api;

import com.bablsoft.accessflow.core.api.ClientApplication;

import java.util.Optional;

/**
 * Resolves the calling application of the current HTTP request (#938). The name stored on the
 * presented API key wins ({@code API_KEY}, trustworthy); otherwise the caller-supplied
 * {@value #HEADER} header is used ({@code HEADER}, client-controlled). Empty when neither is present
 * or when called off the request thread. Identification and audit only — never an authorization
 * input.
 */
public interface RequestApplicationService {

    String HEADER = "X-AccessFlow-Application";

    int MAX_LENGTH = 100;

    Optional<ClientApplication> current();
}
