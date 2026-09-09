package com.bablsoft.accessflow.bootstrap.internal.spec;

/**
 * The bootstrapped organization. {@code governsApis} / {@code governsDeployments} are the
 * governance-domain hints (AF-898) that decide which discovery surfaces the frontend offers
 * (#926); a {@code null} leaves whatever the row already holds, so an operator who does not set
 * them keeps the provisioning default (both off, database governance only).
 */
public record OrganizationSpec(
        String name,
        String slug,
        Boolean governsApis,
        Boolean governsDeployments
) {
}
