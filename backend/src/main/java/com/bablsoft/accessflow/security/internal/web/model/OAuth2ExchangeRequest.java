package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OAuth2ExchangeRequest(
        @NotBlank(message = "{validation.oauth2_exchange.code.required}")
        @Size(max = 256, message = "{validation.oauth2_exchange.code.max}")
        String code) {
}
