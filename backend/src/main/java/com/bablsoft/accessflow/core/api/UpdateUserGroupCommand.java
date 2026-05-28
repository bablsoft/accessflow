package com.bablsoft.accessflow.core.api;

public record UpdateUserGroupCommand(
        String name,
        String description
) {}
