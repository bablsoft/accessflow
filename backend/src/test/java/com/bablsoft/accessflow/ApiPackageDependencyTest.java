package com.bablsoft.accessflow;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.bablsoft.accessflow",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class ApiPackageDependencyTest {

    @ArchTest
    static final ArchRule api_packages_depend_only_on_jdk_and_project_classes =
            noClasses().that()
                    // Wildcard, not an enumerated list: `*` matches exactly one package
                    // component, so this covers every current and future
                    // com.bablsoft.accessflow.<module>.api package automatically. A
                    // hand-maintained list silently under-covers — five modules
                    // (attestation, dashboard, discovery, lifecycle, requestgroups) went
                    // unchecked that way.
                    .resideInAnyPackage("com.bablsoft.accessflow.*.api..")
                    .should()
                    .dependOnClassesThat()
                    .resideOutsideOfPackages(
                            "java..",
                            "javax..",
                            "com.bablsoft.accessflow..",
                            "org.springframework.modulith..")
                    .because("Module api/ packages must depend only on JDK and AccessFlow project classes "
                            + "(plus the @NamedInterface marker from Spring Modulith); see CLAUDE.md → "
                            + "'Module API purity'.");
}
