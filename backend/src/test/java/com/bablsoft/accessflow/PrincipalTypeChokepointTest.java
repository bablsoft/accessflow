package com.bablsoft.accessflow;

import com.bablsoft.accessflow.core.api.PrincipalType;
import com.bablsoft.accessflow.core.api.UserAdminService;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.util.UUID;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * {@code users.principal_type} and the {@code service_accounts} detail row must never drift (#868).
 * The only production caller of {@link UserAdminService#setPrincipalType} is
 * {@code serviceaccounts.internal.DefaultServiceAccountProvisioningService}, which pairs the flip
 * with the detail-row upsert in one transaction. This rule turns that Javadoc promise into a build
 * failure: a new caller anywhere else in the codebase — including inside {@code core} — fails here.
 */
@AnalyzeClasses(packages = "com.bablsoft.accessflow",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class PrincipalTypeChokepointTest {

    @ArchTest
    static final ArchRule principal_type_is_written_only_by_the_serviceaccounts_module =
            noClasses().that()
                    .resideOutsideOfPackage("com.bablsoft.accessflow.serviceaccounts.internal..")
                    .should().callMethod(UserAdminService.class, "setPrincipalType",
                            UUID.class, UUID.class, PrincipalType.class)
                    .because("the principal_type discriminator and the service_accounts detail row "
                            + "are kept in step by ServiceAccountProvisioningService.ensureRegistered; "
                            + "see CLAUDE.md → serviceaccounts and docs/07-security.md → Service accounts");
}
