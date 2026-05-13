package com.bablsoft.accessflow.security.internal.web.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record TestSystemSmtpRequest(
        @Email(message = "{validation.email.invalid}")
        @Size(max = 255, message = "{validation.display_name.max}") String to,
        @Size(max = 255, message = "{validation.display_name.max}") String host,
        @Min(value = 1, message = "{validation.system_smtp.port.range}")
        @Max(value = 65535, message = "{validation.system_smtp.port.range}") Integer port,
        @Size(max = 255, message = "{validation.display_name.max}") String username,
        @Size(max = 1024, message = "{validation.display_name.max}") String smtpPassword,
        Boolean tls,
        @Email(message = "{validation.system_smtp.from_address.email}")
        @Size(max = 255, message = "{validation.display_name.max}") String fromAddress,
        @Size(max = 255, message = "{validation.display_name.max}") String fromName
) {}
