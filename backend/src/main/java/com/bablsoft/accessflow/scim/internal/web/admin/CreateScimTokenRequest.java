package com.bablsoft.accessflow.scim.internal.web.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

record CreateScimTokenRequest(
        @NotBlank(message = "{validation.scim.token_name_required}")
        @Size(max = 100, message = "{validation.scim.token_name_size}")
        String name) {
}
