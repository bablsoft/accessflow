package com.bablsoft.accessflow.core.api;

public record SetupCommand(
        String organizationName,
        String email,
        String displayName,
        String passwordHash
) {}
