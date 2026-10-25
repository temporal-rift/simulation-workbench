package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.GameSession;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.LaneFit;
import io.github.temporalrift.workbench.execution.domain.run.ServedBundle;
import io.github.temporalrift.workbench.execution.support.Provenances;

class CompositeLaneProviderTest {

    private static final BundleDigests BUNDLE = new BundleDigests("a".repeat(64), "b".repeat(64));
    private static final LaneFit FIT = Provenances.fitOf(BUNDLE);

    private final Fake http = new Fake("http-1");
    private final Fake simulator = new Fake("simulator-1");
    private final CompositeLaneProvider composite = new CompositeLaneProvider(List.of(http, simulator));

    /** A provider with one lane that is free until acquired. */
    private static final class Fake implements LaneProvider {
        private final String laneId;
        private boolean free = true;
        private boolean prepared;

        private Fake(String laneId) {
            this.laneId = laneId;
        }

        @Override
        public Optional<CaseLane> acquire(LaneFit fit, String preferredLaneId) {
            return take();
        }

        @Override
        public Optional<CaseLane> acquireExactly(String id, LaneFit fit) {
            return id.equals(laneId) ? take() : Optional.empty();
        }

        private Optional<CaseLane> take() {
            if (!free) {
                return Optional.empty();
            }
            free = false;
            return Optional.of(new ScriptedLane(laneId));
        }

        @Override
        public List<ServedBundle> servedBundles() {
            return List.of(ServedBundle.ofLane(BUNDLE, Provenances.sample()));
        }

        @Override
        public Map<String, LaneFit> freeLanes() {
            return free ? Map.of(laneId, FIT) : Map.of();
        }

        @Override
        public void prepare() {
            prepared = true;
        }
    }

    private record ScriptedLane(String laneId) implements CaseLane {
        @Override
        public GameSession open(CaseContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            // A scripted lane holds nothing to release.
        }
    }

    @Test
    void aCaseGetsTheFirstProviderThatHasALaneFree() {
        assertThat(composite.acquire(FIT, null).orElseThrow().laneId()).isEqualTo("http-1");
        assertThat(composite.acquire(FIT, null).orElseThrow().laneId()).isEqualTo("simulator-1");
        assertThat(composite.acquire(FIT, null)).isEmpty();
    }

    @Test
    void aRecoveringAttemptGetsBackTheProviderOfItsPreviousFreeLane() {
        assertThat(composite.acquire(FIT, "simulator-1").orElseThrow().laneId()).isEqualTo("simulator-1");
    }

    @Test
    void aReproductionAcquiresExactlyTheLaneThatPlayedTheCase() {
        assertThat(composite.acquireExactly("simulator-1", FIT).orElseThrow().laneId())
                .isEqualTo("simulator-1");
        assertThat(composite.acquireExactly("unknown", FIT)).isEmpty();
    }

    @Test
    void servedBundlesCountTheLanesOfEveryProvider() {
        assertThat(composite.servedBundles())
                .singleElement()
                .satisfies(served -> assertThat(served.laneCount()).isEqualTo(2));
    }

    @Test
    void freeLanesAreThoseOfEveryProvider() {
        assertThat(composite.freeLanes()).containsOnlyKeys("http-1", "simulator-1");
    }

    @Test
    void preparingReachesEveryProvider() {
        composite.prepare();

        assertThat(http.prepared && simulator.prepared).isTrue();
    }
}
