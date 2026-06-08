package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DriverTypeInfo;

import java.util.List;

public record ConnectorListResponse(List<ConnectorResponse> connectors) {

    public static ConnectorListResponse from(List<DriverTypeInfo> infos) {
        return new ConnectorListResponse(infos.stream().map(ConnectorResponse::from).toList());
    }
}
