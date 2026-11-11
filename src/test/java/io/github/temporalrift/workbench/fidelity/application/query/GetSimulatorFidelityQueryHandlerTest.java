package io.github.temporalrift.workbench.fidelity.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.fidelity.domain.EdgeVariantCatalog;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheck;
import io.github.temporalrift.workbench.fidelity.domain.FidelityFailure;
import io.github.temporalrift.workbench.fidelity.domain.SimulatorNotAvailableException;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityEnvironments;
import io.github.temporalrift.workbench.fidelity.infrastructure.adapter.out.configuration.ConfiguredEdgeVariants;
import io.github.temporalrift.workbench.fidelity.support.FidelityFixtures;
import io.github.temporalrift.workbench.fidelity.support.InMemoryFidelityRepository;

class GetSimulatorFidelityQueryHandlerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final InMemoryFidelityRepository checks = new InMemoryFidelityRepository();

    /** The parameter space new checks use, which the gate answers for. */
    private static final int NEWEST_SPACE = 2;

    private final EdgeVariantCatalog catalog = ConfiguredEdgeVariants.load(new ObjectMapper());

    private GetSimulatorFidelityQueryHandler handlerFor(String simulatorRevision) {
        return new GetSimulatorFidelityQueryHandler(checks, FidelityFixtures.environments(simulatorRevision), catalog);
    }

    private FidelityCheck stored(String revision, boolean passes) {
        return stored(revision, passes, 1);
    }

    private FidelityCheck stored(String revision, boolean passes, int finishedAfterSeconds) {
        return stored(revision, passes, finishedAfterSeconds, NEWEST_SPACE);
    }

    private FidelityCheck stored(String revision, boolean passes, int finishedAfterSeconds, int parameterSpaceVersion) {
        var queued = FidelityCheck.queued(
                UUID.randomUUID(),
                "42",
                parameterSpaceVersion,
                FidelityFixtures.environment(FidelityFixtures.SIMULATOR_IMAGE, revision),
                FidelityFixtures.environment(FidelityFixtures.LANE_IMAGE, revision),
                "lane-1",
                List.of(),
                NOW);
        checks.create(UUID.randomUUID(), "hash", queued);
        var running = queued.begun(UUID.randomUUID(), UUID.randomUUID(), NOW);
        var finishedAt = NOW.plusSeconds(finishedAfterSeconds);
        var finished = passes ? running.passed(finishedAt) : running.failedByCases(finishedAt);
        checks.update(running, queued.state());
        checks.update(finished, running.state());
        return finished;
    }

    @Test
    void aPassedCheckForTheSimulatorsRevisionsEstablishesIt() {
        var passed = stored("1111111", true);

        var answer = handlerFor("1111111").handle();

        assertThat(answer.established()).isTrue();
        assertThat(answer.passingCheck().check().id()).isEqualTo(passed.id());
        assertThat(answer.sourceRevisions().gameService()).isEqualTo("1111111");
    }

    @Test
    void aRecordForOtherRevisionsDoesNotEstablishTheSimulator() {
        stored("1111111", true);

        var answer = handlerFor("2222222").handle();

        assertThat(answer.established()).isFalse();
        assertThat(answer.passingCheck()).isNull();
    }

    @Test
    void aCheckThatPassedOnAnOlderSpaceDoesNotVouchForTheNewestOne() {
        stored("1111111", true, 1, 1);

        var answer = handlerFor("1111111").handle();

        assertThat(answer.established()).isFalse();
        assertThat(answer.passingCheck()).isNull();
    }

    @Test
    void aCheckThatPassedOnTheNewestSpaceEstablishesIt() {
        var older = stored("1111111", true, 1, 1);
        var newest = stored("1111111", true, 2, 2);

        var answer = handlerFor("1111111").handle();

        assertThat(answer.established()).isTrue();
        assertThat(answer.passingCheck().check().id()).isEqualTo(newest.id()).isNotEqualTo(older.id());
        assertThat(answer.passingCheck().check().parameterSpaceVersion()).isEqualTo(2);
    }

    @Test
    void aPassOnTheNewestSpaceForOtherRevisionsDoesNotEstablish() {
        stored("1111111", true);

        assertThat(handlerFor("2222222").handle().established()).isFalse();
    }

    @Test
    void aFailedCheckDoesNotEstablishTheSimulator() {
        stored("1111111", false);

        assertThat(handlerFor("1111111").handle().established()).isFalse();
    }

    @Test
    void aCheckThatCouldNotCompleteDoesNotEstablishTheSimulator() {
        var queued = FidelityCheck.queued(
                UUID.randomUUID(),
                "42",
                NEWEST_SPACE,
                FidelityFixtures.environment(FidelityFixtures.SIMULATOR_IMAGE, "1111111"),
                FidelityFixtures.environment(FidelityFixtures.LANE_IMAGE, "1111111"),
                "lane-1",
                List.of(),
                NOW);
        checks.create(UUID.randomUUID(), "hash", queued);
        checks.update(queued.failed(new FidelityFailure("RUN_FAILED", "no"), NOW), queued.state());

        assertThat(handlerFor("1111111").handle().established()).isFalse();
    }

    @Test
    void theNewestPassedCheckIsTheRecord() {
        stored("1111111", true);
        var newest = stored("1111111", true, 2);

        assertThat(handlerFor("1111111").handle().passingCheck().check().id()).isEqualTo(newest.id());
    }

    @Test
    void withoutASimulatorTheAnswerIsRefused() {
        var handler = new GetSimulatorFidelityQueryHandler(
                checks,
                new FidelityEnvironments() {
                    @Override
                    public Optional<Simulator> simulator() {
                        return Optional.empty();
                    }

                    @Override
                    public boolean isEquivalentLane(String laneId) {
                        return false;
                    }

                    @Override
                    public Optional<RealLane> equivalentLane() {
                        return Optional.empty();
                    }
                },
                catalog);

        assertThatThrownBy(handler::handle).isInstanceOf(SimulatorNotAvailableException.class);
    }
}
