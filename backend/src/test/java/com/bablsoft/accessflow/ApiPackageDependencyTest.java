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
                    .resideInAnyPackage(
                            "com.bablsoft.accessflow.ai.api..",
                            "com.bablsoft.accessflow.audit.api..",
                            "com.bablsoft.accessflow.core.api..",
                            "com.bablsoft.accessflow.mcp.api..",
                            "com.bablsoft.accessflow.notifications.api..",
                            "com.bablsoft.accessflow.proxy.api..",
                            "com.bablsoft.accessflow.security.api..",
                            "com.bablsoft.accessflow.workflow.api..")
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
