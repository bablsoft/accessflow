package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.access.api.AccessRequestService.DatasourceOption;

import java.util.UUID;

public record RequestableDatasourceResponse(UUID id, String name) {

    public static RequestableDatasourceResponse from(DatasourceOption option) {
        return new RequestableDatasourceResponse(option.id(), option.name());
    }
}
