package io.github.temporalrift.workbench.analysis.domain.game;

import io.github.temporalrift.workbench.shared.game.EndReason;

/**
 * How a winner won: the participant contract's win types of a normal victory, or the special ending that decided the
 * winners by highest score.
 */
public enum WinPath {
    FACTION_OBJECTIVE,
    SCORE_THRESHOLD,
    LAST_PLAYER_STANDING,
    TIMELINE_STABILIZED,
    TIMELINE_COLLAPSED;

    /** The path of a winner of a special ending, or null when the ending is not one. */
    public static WinPath ofSpecialEnding(EndReason endReason) {
        return switch (endReason) {
            case TIMELINE_STABILIZED -> TIMELINE_STABILIZED;
            case TIMELINE_COLLAPSED -> TIMELINE_COLLAPSED;
            default -> null;
        };
    }
}
