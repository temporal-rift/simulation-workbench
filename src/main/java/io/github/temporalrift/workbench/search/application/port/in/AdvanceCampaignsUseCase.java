package io.github.temporalrift.workbench.search.application.port.in;

/** Moves the campaigns that are not over forward, one persisted step at a time. */
public interface AdvanceCampaignsUseCase {

    /** Advances the oldest campaign that can move by one step; false when none could. */
    boolean advance();

    /**
     * Prepares campaigns for a process that has just started: time the process was down is not active time, so the
     * running campaigns count their active time from now on.
     */
    void recover();
}
