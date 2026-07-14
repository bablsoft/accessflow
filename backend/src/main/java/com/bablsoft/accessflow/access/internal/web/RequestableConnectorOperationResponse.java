package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.access.api.AccessRequestService.ConnectorOperationOption;

public record RequestableConnectorOperationResponse(
        String operationId,
        String verb,
        String path,
        String summary,
        boolean write) {

    public static RequestableConnectorOperationResponse from(ConnectorOperationOption option) {
        return new RequestableConnectorOperationResponse(option.operationId(), option.verb(),
                option.path(), option.summary(), option.write());
    }
}
