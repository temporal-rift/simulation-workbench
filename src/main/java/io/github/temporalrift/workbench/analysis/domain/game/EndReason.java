package io.github.temporalrift.workbench.analysis.domain.game;

/** How an eligible game ended; the endings without winners are valid outcomes, not failures. */
public enum EndReason {
    WIN_CONDITION_MET,
    TIMELINE_COLLAPSED,
    TIMELINE_STABILIZED,
    DECK_EXHAUSTED,
    RESOLUTION_FAILED,
    ALL_PLAYERS_ABANDONED
}
