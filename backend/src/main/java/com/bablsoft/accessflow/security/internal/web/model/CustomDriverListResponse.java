package com.bablsoft.accessflow.security.internal.web.model;

import com.bablsoft.accessflow.core.api.CustomDriverView;

import java.util.List;

public record CustomDriverListResponse(List<CustomDriverResponse> drivers) {

    public static CustomDriverListResponse from(List<CustomDriverView> views) {
        return new CustomDriverListResponse(views.stream().map(CustomDriverResponse::from).toList());
    }
}
