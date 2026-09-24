package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.serviceaccounts.api.IssueServiceAccountKeyCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record IssueServiceAccountKeyRequest(
        @NotBlank(message = "{validation.service_account_key_name.required}")
        @Size(max = 100, message = "{validation.service_account_key_name.size}")
        String name,

        Instant expiresAt,

        @Size(max = 100, message = "{validation.api_key.application_name.size}")
        @Pattern(regexp = "[^\\p{Cntrl}]*", message = "{validation.api_key.application_name.pattern}")
        String applicationName
) {
    public IssueServiceAccountKeyCommand toCommand() {
        return new IssueServiceAccountKeyCommand(name, expiresAt, applicationName);
    }
}
