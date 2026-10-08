package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.util.Optional;

/** Reads from a game's accepted state whether a seat's decision in a window was spent. */
@FunctionalInterface
public interface SlotReconciler {

    /**
     * Whether the service holds the seat's decision in the window; empty while accepted state is not
     * current, when neither conclusion may be drawn.
     */
    Optional<Boolean> holds(int seatIndex, String windowKey);
}
