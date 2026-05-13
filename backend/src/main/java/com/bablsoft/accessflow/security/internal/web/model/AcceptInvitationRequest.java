package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AcceptInvitationRequest(
        @NotBlank(message = "{validation.password.size}")
        @Size(min = 8, max = 128, message = "{validation.password.size}") String password,
        @Size(max = 255, message = "{validation.display_name.max}") String displayName
) {}
