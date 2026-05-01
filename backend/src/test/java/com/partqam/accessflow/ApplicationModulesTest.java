package com.partqam.accessflow;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ApplicationModulesTest {

    @Test
    void verifyModuleBoundaries() {
        ApplicationModules.of(AccessFlowApplication.class).verify();
    }
}
