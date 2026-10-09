package io.github.temporalrift.workbench.execution;

import java.util.List;

/**
 * The retained evidence of one game: every seat's steps in the order they were sent, and the raw events
 * deduplicated by source and event identifier, listed per source in offset order.
 */
public record CaseEvidence(List<Step> steps, List<Event> events) {

    public CaseEvidence {
        steps = List.copyOf(steps);
        events = List.copyOf(events);
    }

    /**
     * A command a seat sent.
     *
     * @param observation canonical JSON of the entitled observation the seat decided from
     * @param decision canonical text of the decision
     * @param outcome {@code ACCEPTED}, {@code REJECTED}, {@code UNACKNOWLEDGED} or {@code NOT_SPENT}
     */
    public record Step(
            int seatIndex,
            String phase,
            Integer era,
            Integer round,
            String observation,
            String decision,
            String outcome,
            String outcomeCode) {}

    /** A raw event exactly as delivered on its source. */
    public record Event(String source, String eventType, String payload) {}
}
