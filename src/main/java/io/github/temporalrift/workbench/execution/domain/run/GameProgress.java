package io.github.temporalrift.workbench.execution.domain.run;

import java.util.List;

/** Where a real game stands once the services and projection have settled. */
public sealed interface GameProgress {

    /** A decision window is open and these seats have not decided yet. */
    record Open(List<Integer> pendingSeats) implements GameProgress {
        public Open {
            pendingSeats = List.copyOf(pendingSeats);
        }
    }

    /** Nothing is waiting on a seat; the game proceeds with logical time or in-flight work. */
    record Waiting() implements GameProgress {}

    /** The game reached its terminal state. */
    record Ended() implements GameProgress {}
}
