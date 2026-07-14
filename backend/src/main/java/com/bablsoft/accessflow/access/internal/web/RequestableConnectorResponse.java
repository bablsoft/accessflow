package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.access.api.AccessRequestService.ConnectorOption;

import java.util.UUID;

public record RequestableConnectorResponse(UUID id, String name, String protocol) {

    public static RequestableConnectorResponse from(ConnectorOption option) {
        return new RequestableConnectorResponse(option.id(), option.name(), option.protocol());
    }
}
