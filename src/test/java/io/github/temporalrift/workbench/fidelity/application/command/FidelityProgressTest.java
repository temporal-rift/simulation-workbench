package io.github.temporalrift.workbench.fidelity.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.fidelity.application.port.in.StartFidelityCheckUseCase;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCaseResult;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheck;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheckState;
import io.github.temporalrift.workbench.fidelity.domain.FidelityExperimentRefusedException;
import io.github.temporalrift.workbench.fidelity.domain.FidelityFailure;
import io.github.temporalrift.workbench.fidelity.domain.FidelityMatrix;
import io.github.temporalrift.workbench.fidelity.domain.FidelityPlaybackRefusedException;
import io.github.temporalrift.workbench.fidelity.domain.FidelityVerdict;
import io.github.temporalrift.workbench.fidelity.domain.FirstDivergence;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityExperiments;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityPlayback;
import io.github.temporalrift.workbench.fidelity.infrastructure.adapter.out.configuration.ConfiguredEdgeVariants;
import io.github.temporalrift.workbench.fidelity.support.FidelityFixtures;
import io.github.temporalrift.workbench.fidelity.support.InMemoryFidelityRepository;
import io.github.temporalrift.workbench.shared.game.Faction;

class FidelityProgressTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID EXPERIMENT = new UUID(1, 1);
    private static final UUID RUN = new UUID(2, 2);

    private final InMemoryFidelityRepository checks = new InMemoryFidelityRepository();
    private final FidelityExperiments experiments = mock(FidelityExperiments.class);
    private final FidelityPlayback playback = mock(FidelityPlayback.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private FidelityFixtures.Environments environments = FidelityFixtures.environments("1111111");
    private UUID checkId;

    @BeforeEach
    void anAcceptedCheck() {
        when(experiments.previewVariant(anyString(), anyInt(), any()))
                .thenAnswer(call -> FidelityFixtures.rules(call.getArgument(2)));
        when(experiments.baselinePolicies())
                .thenReturn(List.of(
                        new FidelityExperiments.Policy("random", "1.0.0", "1".repeat(64)),
                        new FidelityExperiments.Policy("faction-greedy", "1.0.0", "2".repeat(64))));
        var edge = ConfiguredEdgeVariants.load(mapper);
        var view = new StartFidelityCheckCommandHandler(checks, environments, experiments, edge, clock)
                .handle(new StartFidelityCheckUseCase.Command(UUID.randomUUID(), "42"));
        checkId = view.check().id();
    }

    private FidelityProgress progress() {
        var settings = new FidelitySettings(3600, 10, 20, 64);
        var edge = ConfiguredEdgeVariants.load(mapper);
        return new FidelityProgress(
                checks,
                environments,
                experiments,
                playback,
                new FidelityManifestFactory(mapper, settings, edge.parameterSpaceVersion()),
                settings,
                clock);
    }

    private FidelityCheck check() {
        return checks.find(checkId).orElseThrow();
    }

    private void givenTheRunStarts() {
        when(experiments.freeze(any(), any())).thenReturn(EXPERIMENT);
        when(playback.startRun(eq(EXPERIMENT), any())).thenReturn(RUN);
    }

    private static List<FidelityPlayback.PlayedCase> played(int count, int failedFrom) {
        var cases = new ArrayList<FidelityPlayback.PlayedCase>();
        var set = FidelityMatrix.factionSets().getFirst();
        for (var index = 0; index < count; index++) {
            var succeeded = index < failedFrom;
            cases.add(new FidelityPlayback.PlayedCase(
                    new UUID(3, index),
                    new UUID(4, index),
                    "baseline",
                    3,
                    set,
                    index % 3,
                    index < count / 2 ? "faction-greedy" : "random",
                    succeeded,
                    succeeded ? null : new FidelityFailure("RUNNER_TIMEOUT", "timed out")));
        }
        return cases;
    }

    private void givenARunWith(List<FidelityPlayback.PlayedCase> cases) {
        givenTheRunStarts();
        when(playback.run(RUN))
                .thenReturn(
                        Optional.of(new FidelityPlayback.RunStatus(FidelityPlayback.RunStatus.Phase.COMPLETED, null)));
        when(playback.cases(RUN)).thenReturn(cases);
    }

    private void reproductionsFinishAs(FidelityPlayback.Reproduced.Status status) {
        when(playback.reproduce(eq(RUN), any(), eq("lane-1"), any()))
                .thenAnswer(call ->
                        UUID.nameUUIDFromBytes(call.getArgument(1).toString().getBytes(StandardCharsets.UTF_8)));
        when(playback.reproduction(any()))
                .thenReturn(Optional.of(new FidelityPlayback.Reproduced(
                        status,
                        status == FidelityPlayback.Reproduced.Status.DIVERGED
                                ? new FirstDivergence(7, "END_REASON", Map.of(), Map.of())
                                : null,
                        status == FidelityPlayback.Reproduced.Status.FAILED
                                ? new FidelityFailure("EXECUTION_FAILED", "crashed")
                                : null)));
    }

    @Test
    void aQueuedCheckFreezesItsExperimentAndStartsItsRun() {
        givenTheRunStarts();

        assertThat(progress().advance()).isTrue();

        var check = check();
        assertThat(check.state()).isEqualTo(FidelityCheckState.RUNNING);
        assertThat(check.experimentId()).isEqualTo(EXPERIMENT);
        assertThat(check.runId()).isEqualTo(RUN);
        assertThat(check.startedAt()).isEqualTo(NOW);
        var manifest = ArgumentCaptor.forClass(ObjectNode.class);
        verify(experiments).freeze(any(), manifest.capture());
        assertThat(manifest.getValue().get("seeds").get(0).asText()).isEqualTo("42");
    }

    @Test
    void aCrashAfterFreezingAsksAgainWithTheSameKeys() {
        when(experiments.freeze(any(), any())).thenReturn(EXPERIMENT);
        when(playback.startRun(eq(EXPERIMENT), any()))
                .thenThrow(new UncheckedIOException(new IOException("the process died")))
                .thenReturn(RUN);
        var progress = progress();

        assertThatThrownBy(progress::advance).isInstanceOf(UncheckedIOException.class);
        assertThat(check().state()).isEqualTo(FidelityCheckState.QUEUED);
        progress.advance();

        var keys = ArgumentCaptor.forClass(UUID.class);
        verify(experiments, times(2)).freeze(keys.capture(), any());
        assertThat(keys.getAllValues())
                .hasSize(2)
                .containsOnly(keys.getAllValues().getFirst());
        assertThat(check().state()).isEqualTo(FidelityCheckState.RUNNING);
    }

    @Test
    void aSimulatorThatChangedRevisionsFailsTheCheckBeforeItBegins() {
        environments = FidelityFixtures.environments("2222222");

        assertThat(progress().advance()).isTrue();

        assertThat(check().state()).isEqualTo(FidelityCheckState.FAILED);
        assertThat(check().failure().code()).isEqualTo("SIMULATOR_NOT_AVAILABLE");
        verify(experiments, never()).freeze(any(), any());
    }

    @Test
    void aLaneThatChangedBeforeTheCheckBeginsFailsItAsUnavailable() {
        environments.laneChanges();

        progress().advance();

        assertThat(check().state()).isEqualTo(FidelityCheckState.FAILED);
        assertThat(check().failure().code()).isEqualTo("SIMULATOR_NOT_AVAILABLE");
        verify(experiments, never()).freeze(any(), any());
    }

    @Test
    void aLaneThatVanishesMidCheckFailsTheCheckInsteadOfJudgingItsCases() {
        givenARunWith(played(3, 3));
        var progress = progress();
        progress.advance();
        progress.advance();
        environments.laneChanges();

        progress.advance();

        assertThat(check().state()).isEqualTo(FidelityCheckState.FAILED);
        assertThat(check().failure().code()).isEqualTo("SIMULATOR_NOT_AVAILABLE");
        assertThat(checks.counts(checkId).unreproducible()).isZero();
        verify(playback, never()).reproduce(any(), any(), any(), any());
    }

    @Test
    void anInterruptedRunIsResumedAndTheCheckKeepsWaiting() {
        givenTheRunStarts();
        progress().advance();
        when(playback.run(RUN))
                .thenReturn(Optional.of(
                        new FidelityPlayback.RunStatus(FidelityPlayback.RunStatus.Phase.INTERRUPTED, null)));

        assertThat(progress().advance()).isTrue();

        verify(playback).resumeRun(RUN);
        assertThat(check().state()).isEqualTo(FidelityCheckState.RUNNING);
        assertThat(checks.caseCount(checkId)).isZero();
    }

    @Test
    void aRunSomethingElseAlreadyResumedIsNotAFailure() {
        givenTheRunStarts();
        progress().advance();
        when(playback.run(RUN))
                .thenReturn(Optional.of(
                        new FidelityPlayback.RunStatus(FidelityPlayback.RunStatus.Phase.INTERRUPTED, null)));
        doThrow(new FidelityPlaybackRefusedException("not interrupted", null))
                .when(playback)
                .resumeRun(RUN);

        progress().advance();

        assertThat(check().state()).isEqualTo(FidelityCheckState.RUNNING);
    }

    @Test
    void aRefusedExperimentFailsTheCheck() {
        when(experiments.freeze(any(), any())).thenThrow(new FidelityExperimentRefusedException("bad", null));

        progress().advance();

        assertThat(check().failure().code()).isEqualTo("EXPERIMENT_REFUSED");
        assertThat(check().verdict()).isEqualTo(FidelityVerdict.FAILED);
    }

    @Test
    void whileTheRunIsActiveNothingChanges() {
        givenTheRunStarts();
        progress().advance();
        when(playback.run(RUN))
                .thenReturn(Optional.of(new FidelityPlayback.RunStatus(FidelityPlayback.RunStatus.Phase.ACTIVE, null)));

        assertThat(progress().advance()).isFalse();
        assertThat(checks.caseCount(checkId)).isZero();
    }

    @Test
    void aFailedRunFailsTheCheckWithItsMessage() {
        givenTheRunStarts();
        progress().advance();
        when(playback.run(RUN))
                .thenReturn(Optional.of(
                        new FidelityPlayback.RunStatus(FidelityPlayback.RunStatus.Phase.FAILED, "lane crashed")));

        progress().advance();

        assertThat(check().failure()).isEqualTo(new FidelityFailure("RUN_FAILED", "lane crashed"));
    }

    @Test
    void aCancelledRunFailsTheCheck() {
        givenTheRunStarts();
        progress().advance();
        when(playback.run(RUN))
                .thenReturn(
                        Optional.of(new FidelityPlayback.RunStatus(FidelityPlayback.RunStatus.Phase.CANCELLED, null)));

        progress().advance();

        assertThat(check().failure().code()).isEqualTo("RUN_CANCELLED");
    }

    @Test
    void aCompletedRunBecomesPendingCasesAndAFailedSimulatorCaseCannotBeReproduced() {
        givenARunWith(played(6, 5));
        progress().advance();

        progress().advance();

        assertThat(checks.caseCount(checkId)).isEqualTo(6);
        var counts = checks.counts(checkId);
        assertThat(counts.pending()).isEqualTo(5);
        assertThat(counts.unreproducible()).isEqualTo(1);
        verify(playback, never()).reproduce(any(), eq(new UUID(3, 5)), any(), any());
    }

    @Test
    void everySucceededCaseIsReproducedOnceOnTheChosenLaneAndAMatchEverywherePassesTheCheck() {
        givenARunWith(played(6, 6));
        reproductionsFinishAs(FidelityPlayback.Reproduced.Status.MATCH);
        var progress = progress();
        progress.advance();
        progress.advance();

        progress.advance();

        verify(playback, times(6)).reproduce(eq(RUN), any(), eq("lane-1"), any());
        assertThat(check().state()).isEqualTo(FidelityCheckState.PASSED);
        assertThat(check().verdict()).isEqualTo(FidelityVerdict.PASSED);
        assertThat(checks.counts(checkId).matched()).isEqualTo(6);
        assertThat(progress.advance()).isFalse();
    }

    @Test
    void oneDivergenceFailsTheCheckAndRecordsWhereItDiverged() {
        givenARunWith(played(3, 3));
        reproductionsFinishAs(FidelityPlayback.Reproduced.Status.DIVERGED);
        var progress = progress();
        progress.advance();
        progress.advance();
        progress.advance();

        assertThat(check().state()).isEqualTo(FidelityCheckState.FAILED);
        assertThat(check().failure()).isNull();
        var diverged = checks.cases(checkId, null, FidelityCaseResult.DIVERGED, 10, 0);
        assertThat(diverged.total()).isEqualTo(3);
        assertThat(diverged.items().getFirst().firstDivergence().kind()).isEqualTo("END_REASON");
    }

    @Test
    void aReproductionThatCouldNotRunMakesItsCaseUnreproducibleAndFailsTheCheck() {
        givenARunWith(played(2, 2));
        reproductionsFinishAs(FidelityPlayback.Reproduced.Status.FAILED);
        var progress = progress();
        progress.advance();
        progress.advance();
        progress.advance();

        assertThat(check().verdict()).isEqualTo(FidelityVerdict.FAILED);
        var unreproducible = checks.cases(checkId, null, FidelityCaseResult.UNREPRODUCIBLE, 10, 0);
        assertThat(unreproducible.total()).isEqualTo(2);
        assertThat(unreproducible.items().getFirst().failure().code()).isEqualTo("EXECUTION_FAILED");
    }

    @Test
    void aReproductionThatIsStillQueuedKeepsTheCheckRunning() {
        givenARunWith(played(2, 2));
        reproductionsFinishAs(FidelityPlayback.Reproduced.Status.QUEUED);
        var progress = progress();
        progress.advance();
        progress.advance();
        progress.advance();

        assertThat(check().state()).isEqualTo(FidelityCheckState.RUNNING);
        assertThat(checks.counts(checkId).pending()).isEqualTo(2);
        assertThat(progress.advance()).isFalse();
    }

    @Test
    void aRefusedReproductionMakesItsCaseUnreproducible() {
        givenARunWith(played(1, 1));
        when(playback.reproduce(any(), any(), any(), any()))
                .thenThrow(new FidelityPlaybackRefusedException("lane differs", null));
        var progress = progress();
        progress.advance();
        progress.advance();
        progress.advance();

        var counts = checks.counts(checkId);
        assertThat(counts.unreproducible()).isEqualTo(1);
        assertThat(check().verdict()).isEqualTo(FidelityVerdict.FAILED);
    }

    @Test
    void reproductionsAreRequestedInBatches() {
        givenARunWith(played(45, 45));
        reproductionsFinishAs(FidelityPlayback.Reproduced.Status.QUEUED);
        var progress = progress();
        progress.advance();
        progress.advance();

        progress.advance();

        verify(playback, times(20)).reproduce(any(), any(), any(), any());
    }

    @Test
    void aRunWithoutCasesFailsTheCheck() {
        givenARunWith(List.of());
        var progress = progress();
        progress.advance();

        progress.advance();

        assertThat(check().state()).isEqualTo(FidelityCheckState.FAILED);
        assertThat(checks.counts(checkId).passes()).isFalse();
    }

    @Test
    void casesAreNumberedInMatrixOrder() {
        givenARunWith(played(4, 4));
        reproductionsFinishAs(FidelityPlayback.Reproduced.Status.QUEUED);
        var progress = progress();
        progress.advance();
        progress.advance();

        var cases = checks.cases(checkId, null, null, 10, 0).items();

        assertThat(cases).extracting("ordinal").containsExactly(0, 1, 2, 3);
        assertThat(cases)
                .extracting("policyId")
                .containsExactly("faction-greedy", "random", "faction-greedy", "random");
        assertThat(cases).extracting("seatRotation").containsExactly(0, 0, 1, 2);
        assertThat(cases.getFirst().factionSet())
                .containsExactlyElementsOf(
                        List.<Faction>copyOf(FidelityMatrix.factionSets().getFirst()));
    }

    @Test
    void withoutAnOpenCheckThereIsNothingToDo() {
        checks.update(check().failed(new FidelityFailure("X", "y"), NOW), FidelityCheckState.QUEUED);

        assertThat(progress().advance()).isFalse();
    }
}
