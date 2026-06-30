package com.bablsoft.accessflow.requestgroups.api;

import java.util.UUID;

public class RequestGroupNotFoundException extends RequestGroupException {

    public RequestGroupNotFoundException(UUID requestGroupId) {
        super("Request group not found: " + requestGroupId);
    }
}
