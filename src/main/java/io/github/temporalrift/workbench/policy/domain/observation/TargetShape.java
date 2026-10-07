package io.github.temporalrift.workbench.policy.domain.observation;

/** Shape of the target a card or special requires, mirroring the published submission schemas. */
public enum TargetShape {
    /** No target; a Decoy declares a disguise category. */
    DISGUISE,
    /** One event and one of its outcomes. */
    EVENT_OUTCOME,
    /** One event and two distinct outcomes of it (source and destination). */
    OUTCOME_PAIR,
    /** A one-to-three event selection. */
    EVENT_LIST,
    /** One other participant. */
    PLAYER,
    /** One or two distinct other participants. */
    PLAYER_LIST
}
