package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SamlExchangeRequest(
        @NotBlank(message = "{validation.saml_exchange.code.required}")
        @Size(max = 256, message = "{validation.saml_exchange.code.max}")
        String code) {
}
