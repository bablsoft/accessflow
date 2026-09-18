package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.serviceaccounts.api.IssueServiceAccountKeyCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record IssueServiceAccountKeyRequest(
        @NotBlank(message = "{validation.service_account_key_name.required}")
        @Size(max = 100, message = "{validation.service_account_key_name.size}")
        String name,

        Instant expiresAt
) {
    public IssueServiceAccountKeyCommand toCommand() {
        return new IssueServiceAccountKeyCommand(name, expiresAt);
    }
}
