package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.GameSession;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.ServedBundle;
import io.github.temporalrift.workbench.execution.support.Provenances;

class CompositeLaneProviderTest {

    private static final BundleDigests BUNDLE = new BundleDigests("a".repeat(64), "b".repeat(64));
    private static final UUID CASE = new UUID(1, 1);

    private final Fake http = new Fake("http-1");
    private final Fake simulator = new Fake("simulator-1");
    private final CompositeLaneProvider composite = new CompositeLaneProvider(List.of(http, simulator));

    /** A provider with one lane that is free until acquired. */
    private static final class Fake implements LaneProvider {
        private final String laneId;
        private boolean free = true;
        private boolean holdsCase;
        private boolean prepared;
        private boolean released;

        private Fake(String laneId) {
            this.laneId = laneId;
        }

        @Override
        public Optional<CaseLane> acquire(UUID caseId, BundleDigests bundle, String preferredLaneId) {
            return take();
        }

        @Override
        public Optional<CaseLane> acquireExactly(String id, BundleDigests bundle) {
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
        public Map<String, BundleDigests> freeLanes() {
            return free ? Map.of(laneId, BUNDLE) : Map.of();
        }

        @Override
        public Set<UUID> heldCases() {
            return holdsCase ? Set.of(CASE) : Set.of();
        }

        @Override
        public void prepare(Collection<Hold> interrupted) {
            prepared = true;
        }

        @Override
        public void releaseHeld(UUID caseId) {
            released = true;
        }
    }

    private record ScriptedLane(String laneId) implements CaseLane {
        @Override
        public GameSession open(CaseContext context, UUID resumeGameId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void retainForRecovery() {}

        @Override
        public void close() {}
    }

    @Test
    void aCaseGetsTheFirstProviderThatHasALaneFree() {
        assertThat(composite.acquire(CASE, BUNDLE, null).orElseThrow().laneId()).isEqualTo("http-1");
        assertThat(composite.acquire(CASE, BUNDLE, null).orElseThrow().laneId()).isEqualTo("simulator-1");
        assertThat(composite.acquire(CASE, BUNDLE, null)).isEmpty();
    }

    @Test
    void aRecoveringAttemptGetsBackTheProviderOfItsPreviousFreeLane() {
        assertThat(composite.acquire(CASE, BUNDLE, "simulator-1").orElseThrow().laneId())
                .isEqualTo("simulator-1");
    }

    @Test
    void aCaseWhoseGameAProviderHoldsGoesToThatProvider() {
        simulator.holdsCase = true;

        assertThat(composite.acquire(CASE, BUNDLE, "http-1").orElseThrow().laneId())
                .isEqualTo("simulator-1");
    }

    @Test
    void aReproductionAcquiresExactlyTheLaneThatPlayedTheCase() {
        assertThat(composite.acquireExactly("simulator-1", BUNDLE).orElseThrow().laneId())
                .isEqualTo("simulator-1");
        assertThat(composite.acquireExactly("unknown", BUNDLE)).isEmpty();
    }

    @Test
    void servedBundlesCountTheLanesOfEveryProvider() {
        assertThat(composite.servedBundles())
                .singleElement()
                .satisfies(served -> assertThat(served.laneCount()).isEqualTo(2));
    }

    @Test
    void freeLanesAndHeldCasesAreThoseOfEveryProvider() {
        simulator.holdsCase = true;

        assertThat(composite.freeLanes()).containsOnlyKeys("http-1", "simulator-1");
        assertThat(composite.heldCases()).containsExactly(CASE);
    }

    @Test
    void preparingAndReleasingReachEveryProvider() {
        composite.prepare(List.of());
        composite.releaseHeld(CASE);

        assertThat(http.prepared && simulator.prepared).isTrue();
        assertThat(http.released && simulator.released).isTrue();
    }
}
