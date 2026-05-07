package com.partqam.accessflow.core.api;

public record SetupCommand(
        String organizationName,
        String email,
        String displayName,
        String passwordHash
) {}
