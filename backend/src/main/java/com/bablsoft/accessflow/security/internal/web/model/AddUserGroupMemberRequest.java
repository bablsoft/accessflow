package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AddUserGroupMemberRequest(
        @NotNull(message = "{validation.user_id.required}") UUID userId
) {}
