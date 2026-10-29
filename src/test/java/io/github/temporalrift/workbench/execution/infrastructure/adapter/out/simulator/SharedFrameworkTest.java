package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import io.github.temporalrift.workbench.SimulationWorkbenchApplication;

class SharedFrameworkTest {

    @Test
    void releasingEmptiesWhatModulithKeptForTheApplicationsItMet() {
        ApplicationModules.of(SimulationWorkbenchApplication.class);
        assertThat(SharedFramework.cached()).isPositive();

        SharedFramework.release();

        assertThat(SharedFramework.cached()).isZero();
    }

    @Test
    void preparingLeavesTheCallersContextClassLoaderInPlaceAndCanBeRepeated() {
        var thread = Thread.currentThread();
        var own = thread.getContextClassLoader();

        SharedFramework.prepare();
        SharedFramework.prepare();

        assertThat(thread.getContextClassLoader()).isSameAs(own);
    }
}
