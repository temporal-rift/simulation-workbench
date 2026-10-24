package io.github.temporalrift.workbench.execution;

import java.util.List;

import io.github.temporalrift.workbench.execution.domain.evidence.ObservedEvent;
import io.github.temporalrift.workbench.execution.domain.evidence.StepRecord;

/**
 * The retained evidence of one game: every seat's steps in the order they were sent, and the raw events
 * deduplicated by source and event identifier, listed per source in offset order.
 */
public record CaseEvidence(List<StepRecord> steps, List<ObservedEvent> events) {

    public CaseEvidence {
        steps = List.copyOf(steps);
        events = List.copyOf(events);
    }
}
