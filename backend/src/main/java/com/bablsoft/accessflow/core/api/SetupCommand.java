package com.bablsoft.accessflow.core.api;

/**
 * First-run setup. {@code governsApis} / {@code governsDeployments} are the onboarding domain hints
 * chosen in the wizard (AF-898) — they decide which checklist steps appear and nothing else.
 */
public record SetupCommand(
        String organizationName,
        String email,
        String displayName,
        String passwordHash,
        boolean governsApis,
        boolean governsDeployments
) {}
