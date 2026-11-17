package io.github.temporalrift.workbench.analysis.domain.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.policy.domain.observation.Board;

/**
 * The workbench maps paradox types by name from the timeline events and the game-state projection, and to its own
 * reports API, so its enums must hold exactly the contracts' values.
 */
class ParadoxTypeContractTest {

    @Test
    void theAnalysisTypesMatchThePublishedTimelineTypes() {
        assertThat(names(ParadoxType.values()))
                .containsExactlyInAnyOrder(names(
                        io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ParadoxType.values()));
    }

    @Test
    void theAnalysisTypesMatchTheReportedTypes() {
        assertThat(names(ParadoxType.values()))
                .containsExactlyInAnyOrder(
                        names(io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model
                                .ParadoxType.values()));
    }

    @Test
    void theObservedKindsMatchTheProjectedTypes() {
        assertThat(names(Board.ParadoxKind.values()))
                .containsExactlyInAnyOrder(names(io.github.temporalrift.workbench.execution.infrastructure.adapter.out
                        .lane.projection.model.ParadoxType.values()));
    }

    private static String[] names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toArray(String[]::new);
    }
}
