package io.github.temporalrift.workbench.execution.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.application.port.in.ReproduceCaseOnLaneUseCase;
import io.github.temporalrift.workbench.execution.domain.evidence.PinnedEvidence;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.ExperimentSource;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.port.out.ReproductionRepository;
import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;
import io.github.temporalrift.workbench.execution.domain.reproduction.LaneNotEquivalentException;
import io.github.temporalrift.workbench.execution.domain.reproduction.ReproductionClaim;
import io.github.temporalrift.workbench.execution.domain.reproduction.ReproductionCreation;
import io.github.temporalrift.workbench.execution.domain.run.Attempt;
import io.github.temporalrift.workbench.execution.domain.run.AttemptState;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.BundleProvenance;
import io.github.temporalrift.workbench.execution.domain.run.CaseResult;
import io.github.temporalrift.workbench.execution.domain.run.CaseRules;
import io.github.temporalrift.workbench.execution.domain.run.FinalScore;
import io.github.temporalrift.workbench.execution.domain.run.LaneFit;
import io.github.temporalrift.workbench.execution.domain.run.LaneProfile;
import io.github.temporalrift.workbench.execution.domain.run.LogicalCase;
import io.github.temporalrift.workbench.execution.domain.run.Run;
import io.github.temporalrift.workbench.execution.domain.run.RunIdempotencyConflictException;
import io.github.temporalrift.workbench.shared.execution.CaseState;
import io.github.temporalrift.workbench.shared.game.EndReason;
import io.github.temporalrift.workbench.shared.game.Faction;

class ReproduceCaseOnLaneCommandHandlerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID RUN = new UUID(1, 1);
    private static final UUID CASE = new UUID(2, 2);
    private static final UUID EXPERIMENT = new UUID(3, 3);
    private static final BundleDigests DIGESTS = new BundleDigests("a".repeat(64), "b".repeat(64));
    private static final List<UUID> BOTS = List.of(new UUID(0, 1), new UUID(0, 2), new UUID(0, 3));

    private final RunRepository runs = mock(RunRepository.class);
    private final ExperimentSource experiments = mock(ExperimentSource.class);
    private final EvidenceLedger evidence = mock(EvidenceLedger.class);
    private final ReproductionRepository reproductions = mock(ReproductionRepository.class);
    private final LaneProvider lanes = mock(LaneProvider.class);
    private final ReproduceCaseOnLaneCommandHandler handler = new ReproduceCaseOnLaneCommandHandler(
            new ReproductionSources(runs, experiments, evidence),
            reproductions,
            lanes,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static LaneProfile profile(LaneProfile.Kind kind, String image, String gameRevision, List<UUID> bots) {
        var game = new BundleProvenance.ServiceBuild(image, gameRevision);
        var other = new BundleProvenance.ServiceBuild(image, "2222222");
        var provenance = new BundleProvenance(
                new BundleProvenance.Artifact("c".repeat(64), null),
                new BundleProvenance.Artifact("d".repeat(64), null),
                game,
                other,
                other,
                new TreeMap<>(Map.of("session-api", "3.0.0")));
        return new LaneProfile(LaneFit.of(DIGESTS.contentDigest(), provenance), DIGESTS, provenance, kind, bots);
    }

    private static final LaneProfile SIMULATOR =
            profile(LaneProfile.Kind.IN_PROCESS, "sha256:" + "1".repeat(64), "1111111", BOTS);

    private void givenASucceededCaseOnTheSimulator() {
        var result = new CaseResult(
                EndReason.WIN_CONDITION_MET,
                List.of(),
                List.of(new FinalScore(0, Faction.ERASERS, 1)),
                1,
                1,
                1,
                "f".repeat(64));
        var logicalCase = new LogicalCase(
                CASE,
                RUN,
                new UUID(4, 4),
                0,
                "baseline",
                DIGESTS,
                SIMULATOR.fit(),
                "42",
                3,
                List.of(),
                CaseState.SUCCEEDED,
                result);
        when(runs.findCase(RUN, CASE)).thenReturn(Optional.of(logicalCase));
        when(runs.find(RUN)).thenReturn(Optional.of(Run.queued(RUN, EXPERIMENT, NOW)));
        when(evidence.pinned(CASE))
                .thenReturn(Optional.of(new PinnedEvidence("m".repeat(64), "{}", "", "f".repeat(64))));
        when(experiments.plan(EXPERIMENT))
                .thenReturn(Optional.of(new ExperimentSource.Plan("m".repeat(64), "{}", 1, 60, 10, List.of())));
        when(experiments.rules(EXPERIMENT, "baseline"))
                .thenReturn(Optional.of(new CaseRules(new TreeMap<>(), "a".repeat(64))));
        when(runs.attemptsOf(CASE))
                .thenReturn(List.of(new Attempt(
                        new UUID(5, 5),
                        CASE,
                        1,
                        AttemptState.SUCCEEDED,
                        new UUID(6, 6),
                        "simulator-1",
                        NOW,
                        NOW,
                        null)));
        when(reproductions.findByKey(any())).thenReturn(Optional.empty());
    }

    private void givenLanes(Map<String, LaneProfile> profiles) {
        when(lanes.profiles()).thenReturn(profiles);
    }

    @Test
    void anEquivalentLaneTakesTheReproductionWithItsOwnFit() {
        givenASucceededCaseOnTheSimulator();
        var real = profile(LaneProfile.Kind.DEPLOYED, "sha256:" + "9".repeat(64), "1111111", BOTS);
        givenLanes(Map.of("simulator-1", SIMULATOR, "lane-1", real));
        when(reproductions.create(any(), anyString(), any())).thenReturn(new ReproductionCreation.Created());

        var reproduction =
                handler.handle(new ReproduceCaseOnLaneUseCase.Command(RUN, CASE, "lane-1", UUID.randomUUID()));

        assertThat(reproduction.laneId()).isEqualTo("lane-1");
        assertThat(reproduction.laneFit()).isEqualTo(real.fit()).isNotEqualTo(SIMULATOR.fit());
        assertThat(reproduction.runId()).isEqualTo(RUN);
        assertThat(reproduction.caseId()).isEqualTo(CASE);
    }

    @Test
    void aLaneOfAnotherSourceRevisionCreatesNothing() {
        givenASucceededCaseOnTheSimulator();
        var other = profile(LaneProfile.Kind.DEPLOYED, "sha256:" + "9".repeat(64), "1111112", BOTS);
        givenLanes(Map.of("simulator-1", SIMULATOR, "lane-1", other));

        var command = new ReproduceCaseOnLaneUseCase.Command(RUN, CASE, "lane-1", UUID.randomUUID());
        assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(LaneNotEquivalentException.class)
                .hasMessageContaining("lane-1")
                .hasMessageContaining("game-service source revision");
        verify(reproductions, never()).create(any(), anyString(), any());
    }

    @Test
    void anUnknownLaneCreatesNothing() {
        givenASucceededCaseOnTheSimulator();
        givenLanes(Map.of("simulator-1", SIMULATOR));

        var command = new ReproduceCaseOnLaneUseCase.Command(RUN, CASE, "lane-9", UUID.randomUUID());
        assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(LaneNotEquivalentException.class)
                .hasMessageContaining("lane-9 is not configured");
        verify(reproductions, never()).create(any(), anyString(), any());
    }

    @Test
    void aRepeatedKeyReturnsTheOriginalReproduction() {
        givenASucceededCaseOnTheSimulator();
        var real = profile(LaneProfile.Kind.DEPLOYED, "sha256:" + "9".repeat(64), "1111111", BOTS);
        givenLanes(Map.of("simulator-1", SIMULATOR, "lane-1", real));
        var key = UUID.randomUUID();
        var original = io.github.temporalrift.workbench.execution.domain.reproduction.Reproduction.queued(
                RUN, CASE, "lane-1", real.fit(), NOW);
        var hash = RunRequestHash.of(ReproduceCaseOnLaneCommandHandler.OPERATION, CASE + "@lane-1");
        when(reproductions.findByKey(key)).thenReturn(Optional.of(new ReproductionClaim(original, hash)));

        var again = handler.handle(new ReproduceCaseOnLaneUseCase.Command(RUN, CASE, "lane-1", key));

        assertThat(again.reproductionId()).isEqualTo(original.reproductionId());
        verify(reproductions, never()).create(any(), anyString(), any());
    }

    @Test
    void aKeyReusedForAnotherLaneConflicts() {
        givenASucceededCaseOnTheSimulator();
        var key = UUID.randomUUID();
        var original = io.github.temporalrift.workbench.execution.domain.reproduction.Reproduction.queued(
                RUN, CASE, "lane-1", SIMULATOR.fit(), NOW);
        var hash = RunRequestHash.of(ReproduceCaseOnLaneCommandHandler.OPERATION, CASE + "@lane-1");
        when(reproductions.findByKey(key)).thenReturn(Optional.of(new ReproductionClaim(original, hash)));

        var command = new ReproduceCaseOnLaneUseCase.Command(RUN, CASE, "lane-2", key);
        assertThatThrownBy(() -> handler.handle(command)).isInstanceOf(RunIdempotencyConflictException.class);
    }
}
