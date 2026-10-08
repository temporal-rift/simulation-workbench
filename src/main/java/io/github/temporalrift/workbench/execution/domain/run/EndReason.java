package io.github.temporalrift.workbench.execution.domain.run;

/** The authoritative reason a game ended, as published by the participant contracts. */
public enum EndReason {
    WIN_CONDITION_MET,
    TIMELINE_COLLAPSED,
    TIMELINE_STABILIZED,
    DECK_EXHAUSTED,
    RESOLUTION_FAILED,
    ALL_PLAYERS_ABANDONED
}
