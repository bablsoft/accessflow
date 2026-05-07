package com.partqam.accessflow.security.internal.web.model;

import com.partqam.accessflow.core.api.DriverTypeInfo;

import java.util.List;

public record DatasourceTypesResponse(List<DatasourceTypeResponse> types) {

    public static DatasourceTypesResponse from(List<DriverTypeInfo> infos) {
        return new DatasourceTypesResponse(infos.stream().map(DatasourceTypeResponse::from).toList());
    }
}
