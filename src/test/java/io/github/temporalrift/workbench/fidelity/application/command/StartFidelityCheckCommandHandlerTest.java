package io.github.temporalrift.workbench.fidelity.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.fidelity.application.port.in.StartFidelityCheckUseCase;
import io.github.temporalrift.workbench.fidelity.domain.EdgeVariants;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheckActiveException;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheckState;
import io.github.temporalrift.workbench.fidelity.domain.FidelityExperimentRefusedException;
import io.github.temporalrift.workbench.fidelity.domain.FidelityIdempotencyConflictException;
import io.github.temporalrift.workbench.fidelity.domain.SimulatorNotAvailableException;
import io.github.temporalrift.workbench.fidelity.domain.VariantLabel;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityEnvironments;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityExperiments;
import io.github.temporalrift.workbench.fidelity.infrastructure.adapter.out.configuration.ConfiguredEdgeVariants;
import io.github.temporalrift.workbench.fidelity.support.FidelityFixtures;
import io.github.temporalrift.workbench.fidelity.support.InMemoryFidelityRepository;

class StartFidelityCheckCommandHandlerTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final InMemoryFidelityRepository checks = new InMemoryFidelityRepository();
    private final FidelityExperiments experiments = mock(FidelityExperiments.class);
    private final EdgeVariants edge = ConfiguredEdgeVariants.load(new ObjectMapper());

    private StartFidelityCheckCommandHandler handler(FidelityEnvironments environments) {
        return new StartFidelityCheckCommandHandler(
                checks, environments, experiments, edge, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private StartFidelityCheckCommandHandler handler() {
        when(experiments.previewVariant(anyString(), anyInt(), any()))
                .thenAnswer(call -> FidelityFixtures.rules(call.getArgument(2)));
        return handler(FidelityFixtures.environments("1111111"));
    }

    @Test
    void aCheckIsAcceptedWithTheThreeVariantsAndBothEnvironments() {
        var view = handler().handle(new StartFidelityCheckUseCase.Command(UUID.randomUUID(), "42"));

        var check = view.check();
        assertThat(check.state()).isEqualTo(FidelityCheckState.QUEUED);
        assertThat(check.seed()).isEqualTo("42");
        assertThat(check.laneId()).isEqualTo("lane-1");
        assertThat(check.simulator().sourceRevisions().gameService()).isEqualTo("1111111");
        assertThat(check.lane().gameService().imageDigest()).isEqualTo(FidelityFixtures.LANE_IMAGE);
        assertThat(check.variants())
                .extracting("label")
                .containsExactly(VariantLabel.BASELINE, VariantLabel.LOW, VariantLabel.HIGH);
        assertThat(check.variants().getFirst().overrides()).isEmpty();
        assertThat(check.variants().get(1).overrides()).isEqualTo(edge.low());
        assertThat(check.variants().get(2).overrides()).isEqualTo(edge.high());
        assertThat(view.counts().total()).isZero();
    }

    @Test
    void aRepeatedKeyWithTheSameSeedReturnsTheOriginalCheck() {
        var handler = handler();
        var key = UUID.randomUUID();

        var first = handler.handle(new StartFidelityCheckUseCase.Command(key, "42"));
        var second = handler.handle(new StartFidelityCheckUseCase.Command(key, "42"));

        assertThat(second.check().id()).isEqualTo(first.check().id());
        assertThat(checks.list(null, 10, 0).total()).isEqualTo(1);
    }

    @Test
    void theSameKeyForAnotherSeedConflicts() {
        var handler = handler();
        var key = UUID.randomUUID();
        handler.handle(new StartFidelityCheckUseCase.Command(key, "42"));

        assertThatThrownBy(() -> handler.handle(new StartFidelityCheckUseCase.Command(key, "43")))
                .isInstanceOf(FidelityIdempotencyConflictException.class);
    }

    @Test
    void aSecondCheckIsRefusedWhileOneIsOpen() {
        var handler = handler();
        handler.handle(new StartFidelityCheckUseCase.Command(UUID.randomUUID(), "42"));

        assertThatThrownBy(() -> handler.handle(new StartFidelityCheckUseCase.Command(UUID.randomUUID(), "43")))
                .isInstanceOf(FidelityCheckActiveException.class);
    }

    @Test
    void withoutASimulatorNothingIsCreated() {
        var handler = handler(new FidelityEnvironments() {
            @Override
            public Optional<Simulator> simulator() {
                return Optional.empty();
            }

            @Override
            public Optional<RealLane> equivalentLane() {
                return Optional.empty();
            }
        });

        assertThatThrownBy(() -> handler.handle(new StartFidelityCheckUseCase.Command(UUID.randomUUID(), "42")))
                .isInstanceOf(SimulatorNotAvailableException.class)
                .hasMessageContaining("not enabled");
        assertThat(checks.list(null, 10, 0).total()).isZero();
    }

    @Test
    void withoutAnEquivalentRealLaneNothingIsCreated() {
        var handler = handler(new FidelityEnvironments() {
            @Override
            public Optional<Simulator> simulator() {
                return Optional.of(FidelityFixtures.simulator("1111111"));
            }

            @Override
            public Optional<RealLane> equivalentLane() {
                return Optional.empty();
            }
        });

        assertThatThrownBy(() -> handler.handle(new StartFidelityCheckUseCase.Command(UUID.randomUUID(), "42")))
                .isInstanceOf(SimulatorNotAvailableException.class)
                .hasMessageContaining("real lane");
        assertThat(checks.list(null, 10, 0).total()).isZero();
    }

    @Test
    void aBaselineThatCannotCarryTheEdgeVariantsMakesTheSimulatorUnavailable() {
        when(experiments.previewVariant(anyString(), anyInt(), any())).thenAnswer(call -> {
            Map<String, BigDecimal> overrides = call.getArgument(2);
            if (overrides.isEmpty()) {
                return FidelityFixtures.rules(overrides);
            }
            throw new FidelityExperimentRefusedException("no baseline value for a parameter", null);
        });
        var handler = handler(FidelityFixtures.environments("1111111"));

        assertThatThrownBy(() -> handler.handle(new StartFidelityCheckUseCase.Command(UUID.randomUUID(), "42")))
                .isInstanceOf(SimulatorNotAvailableException.class)
                .hasMessageContaining("low variant");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "01", "4.2", "forty", "", "18446744073709551616"})
    void aSeedThatIsNotAnUnsignedSixtyFourBitDecimalIsRefused(String seed) {
        var handler = handler();

        assertThatThrownBy(() -> handler.handle(new StartFidelityCheckUseCase.Command(UUID.randomUUID(), seed)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("seed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "42", "18446744073709551615"})
    void theLargestAndSmallestSeedsAreAccepted(String seed) {
        var view = handler().handle(new StartFidelityCheckUseCase.Command(UUID.randomUUID(), seed));

        assertThat(view.check().seed()).isEqualTo(seed);
    }
}
