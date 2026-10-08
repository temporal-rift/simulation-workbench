package io.github.temporalrift.workbench.execution.application.port.in;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.evidence.ObservedEvent;
import io.github.temporalrift.workbench.execution.domain.evidence.StepRecord;

/** Reads an ordered, perspective-safe page of a case's retained evidence. */
public interface GetCaseReplayUseCase {

    /**
     * @throws io.github.temporalrift.workbench.execution.domain.evidence.InvalidReplayPerspectiveException when
     *     the perspective and seat do not fit
     */
    ReplayPage handle(Query query);

    /**
     * @param seatIndex the seat a PLAYER replay follows; absent for OBSERVER
     * @param afterStep the last step the reader already has; absent to start from the beginning
     * @param limit the most steps to return
     */
    record Query(UUID runId, UUID caseId, Perspective perspective, Integer seatIndex, Integer afterStep, int limit) {}

    enum Perspective {
        /** One seat's own entitled observations and commands, nothing hidden from it. */
        PLAYER,
        /** Every seat plus the raw events; for authorized designers only, never an input to a policy. */
        OBSERVER
    }

    /**
     * @param nextStep the {@code afterStep} that continues the replay, or null when the page is the last
     */
    record ReplayPage(
            UUID caseId, String manifestDigest, Perspective perspective, List<Entry> entries, Integer nextStep) {
        public ReplayPage {
            entries = List.copyOf(entries);
        }
    }

    /** One replay step: a command a seat sent, or (observer only) a raw event. */
    sealed interface Entry {

        int step();

        record Command(int step, StepRecord record) implements Entry {}

        /** Events are listed per source in offset order; their position carries no order across sources. */
        record Event(int step, ObservedEvent event) implements Entry {}
    }
}
