package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.ChainState;

/** The confirmed link events and the cascaded events a lane serving {@code projection-api} 10.6.0 reports. */
class BoardMapperProjectionFactsTest {

    private static final int SEAT = 1;
    private static final UUID LINKED = new UUID(0, 0x51);
    private static final UUID SECOND = new UUID(0, 0x52);
    private static final UUID CASCADED = new UUID(0, 0x61);

    @Test
    void theConfirmedLinkEventsAndTheCascadedEventsReachTheBoard() {
        var state = ProjectionStates.actionRound(2, 2, List.of());
        var chain = new ChainState(ChainState.StatusEnum.ACTIVE, 2);
        chain.setConfirmedEventIds(new ArrayList<>(List.of(LINKED, SECOND)));
        state.setChain(chain);
        state.setCascadedEventIds(new ArrayList<>(List.of(CASCADED)));

        var board = ObservationMapper.owed(SEAT, ProjectionStates.ME, state)
                .orElseThrow()
                .observation()
                .board();

        assertThat(board.chain().confirmedEventIds()).containsExactly(LINKED, SECOND);
        assertThat(board.cascadedEventIds()).containsExactly(CASCADED);
    }

    @Test
    void aLaneThatReportsNeitherReadsAsHavingNone() {
        var state = ProjectionStates.actionRound(2, 2, List.of());
        state.setChain(new ChainState(ChainState.StatusEnum.ACTIVE, 2));

        var board = ObservationMapper.owed(SEAT, ProjectionStates.ME, state)
                .orElseThrow()
                .observation()
                .board();

        assertThat(board.chain().confirmedEventIds()).isEmpty();
        assertThat(board.cascadedEventIds()).isEmpty();
    }
}
