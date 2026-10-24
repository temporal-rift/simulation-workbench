package io.github.temporalrift.workbench.execution.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.github.temporalrift.workbench.execution.application.port.in.StartRunUseCase;
import io.github.temporalrift.workbench.execution.domain.port.out.ExperimentSource;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.CaseCounts;
import io.github.temporalrift.workbench.execution.domain.run.Run;
import io.github.temporalrift.workbench.execution.domain.run.RunCreation;
import io.github.temporalrift.workbench.execution.domain.run.ServedBundle;
import io.github.temporalrift.workbench.execution.domain.run.UnservedVariantsException;
import io.github.temporalrift.workbench.execution.support.Provenances;

class StartRunCommandHandlerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID EXPERIMENT = new UUID(1, 1);
    private static final BundleDigests BASELINE = new BundleDigests("a".repeat(64), "b".repeat(64));
    private static final BundleDigests CANDIDATE = new BundleDigests("f".repeat(64), "b".repeat(64));
    private static final BundleDigests OTHER = new BundleDigests("e".repeat(64), "b".repeat(64));

    private final ExperimentSource experiments = mock(ExperimentSource.class);
    private final RunRepository runs = mock(RunRepository.class);
    private final LaneProvider lanes = mock(LaneProvider.class);
    private final StartRunCommandHandler handler =
            new StartRunCommandHandler(experiments, runs, lanes, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void everyCaseIsBoundToItsVariantsBundle() {
        givenExperiment(
                List.of(variant("baseline", BASELINE), variant("candidate", CANDIDATE), variant("third", OTHER)));
        when(lanes.servedBundles()).thenReturn(served(BASELINE, CANDIDATE, OTHER));
        when(runs.create(any(), anyString(), any(), anyInt(), any())).thenReturn(new RunCreation.Created());
        when(runs.find(any())).thenAnswer(call -> Optional.of(Run.queued(call.getArgument(0), EXPERIMENT, NOW)));
        when(runs.counts(any())).thenReturn(new CaseCounts(2, 2, 0, 0, 0, 0));

        handler.handle(new StartRunUseCase.Command(EXPERIMENT, UUID.randomUUID()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RunRepository.NewCase>> cases = ArgumentCaptor.forClass(List.class);
        verify(runs).create(any(), anyString(), any(), anyInt(), cases.capture());
        assertThat(cases.getValue())
                .extracting(RunRepository.NewCase::variantLabel, RunRepository.NewCase::bundle)
                .containsExactly(tuple("baseline", BASELINE), tuple("candidate", CANDIDATE));
    }

    @Test
    void startingReadsTheExperimentOnceAsOneSnapshot() {
        givenExperiment(List.of(variant("baseline", BASELINE), variant("candidate", CANDIDATE)));
        when(lanes.servedBundles()).thenReturn(served(BASELINE, CANDIDATE));
        when(runs.create(any(), anyString(), any(), anyInt(), any())).thenReturn(new RunCreation.Created());
        when(runs.find(any())).thenAnswer(call -> Optional.of(Run.queued(call.getArgument(0), EXPERIMENT, NOW)));
        when(runs.counts(any())).thenReturn(new CaseCounts(2, 2, 0, 0, 0, 0));

        handler.handle(new StartRunUseCase.Command(EXPERIMENT, UUID.randomUUID()));

        verify(experiments).snapshot(EXPERIMENT);
        verifyNoMoreInteractions(experiments);
    }

    @Test
    void anExperimentWithUnservedVariantsCreatesNothingAndNamesThem() {
        givenExperiment(
                List.of(variant("baseline", BASELINE), variant("candidate", CANDIDATE), variant("third", OTHER)));
        when(lanes.servedBundles()).thenReturn(served(BASELINE));

        assertThatThrownBy(() -> handler.handle(new StartRunUseCase.Command(EXPERIMENT, UUID.randomUUID())))
                .isInstanceOfSatisfying(
                        UnservedVariantsException.class,
                        e -> assertThat(e.labels()).containsExactly("candidate", "third"))
                .hasMessageContaining("candidate, third");
        verify(runs, never()).create(any(), anyString(), any(), anyInt(), any());
    }

    private void givenExperiment(List<ExperimentSource.Variant> variants) {
        when(runs.findCommand(any())).thenReturn(Optional.empty());
        when(experiments.snapshot(EXPERIMENT))
                .thenReturn(Optional.of(new ExperimentSource.Snapshot(
                        new ExperimentSource.Plan("digest", "{}", 1, 60, 1, variants),
                        List.of(
                                new ExperimentSource.PlannedCase(new UUID(2, 2), "42", "baseline", 3, List.of()),
                                new ExperimentSource.PlannedCase(new UUID(3, 3), "42", "candidate", 3, List.of())))));
    }

    private static ExperimentSource.Variant variant(String label, BundleDigests bundle) {
        return new ExperimentSource.Variant(label, bundle);
    }

    private static List<ServedBundle> served(BundleDigests... bundles) {
        return ServedBundle.counted(Stream.of(bundles)
                .map(bundle -> ServedBundle.ofLane(bundle, Provenances.sample()))
                .toList());
    }
}
