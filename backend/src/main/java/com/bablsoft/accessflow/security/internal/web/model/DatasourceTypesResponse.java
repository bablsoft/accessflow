package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.DriverTypeInfo;

import java.util.List;

public record DatasourceTypesResponse(List<DatasourceTypeResponse> types) {

    public static DatasourceTypesResponse from(List<DriverTypeInfo> infos) {
        return new DatasourceTypesResponse(infos.stream().map(DatasourceTypeResponse::from).toList());
    }
}
