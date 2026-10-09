package io.github.temporalrift.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTest {

    @Test
    void modulesOnlyReachEachOtherThroughTheirPublicApis() {
        ApplicationModules.of(SimulationWorkbenchApplication.class).verify();
    }
}
