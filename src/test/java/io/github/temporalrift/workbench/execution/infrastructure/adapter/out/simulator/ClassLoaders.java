package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.List;

import org.awaitility.Awaitility;

/** Checks that class loaders a slot let go of are not reachable from anything the JVM keeps. */
final class ClassLoaders {

    private ClassLoaders() {}

    /** Collects until every loader is gone, failing with the loaders still reachable after the wait. */
    static void awaitCollected(List<WeakReference<ClassLoader>> loaders) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    System.gc();
                    assertThat(loaders.stream().map(WeakReference::get).filter(loader -> loader != null))
                            .as("service class loaders still reachable after a full collection")
                            .isEmpty();
                });
    }
}
