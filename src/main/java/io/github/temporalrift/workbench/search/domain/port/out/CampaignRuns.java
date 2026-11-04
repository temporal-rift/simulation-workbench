package io.github.temporalrift.workbench.search.domain.port.out;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.temporalrift.workbench.shared.execution.RunPhase;

/** Driven port to the execution module: the runs that play a campaign's waves. */
public interface CampaignRuns {

    /**
     * Creates the run of a frozen experiment, once per idempotency key, and returns its id.
     *
     * @throws io.github.temporalrift.workbench.search.domain.campaign.CampaignPlayRefusedException when no lane fits
     */
    UUID start(UUID experimentId, UUID idempotencyKey);

    /** Where the run stands, or empty for an unknown run. */
    Optional<RunProgress> progress(UUID runId);

    /** Holds back or allows cases: cases already executing finish. A run that is over ignores it. */
    void setConcurrency(UUID runId, int concurrency);

    /** Resumes a run a restart interrupted. A run that is not interrupted ignores it. */
    void resume(UUID runId);

    /** Stops scheduling the run's cases, keeping every completed result. A run that is over ignores it. */
    void cancel(UUID runId);

    /** The seeds the variant has played to the end: every case of the seed is over. */
    Set<String> finishedSeeds(UUID runId, String variantLabel);

    /**
     * @param failureMessage why the run failed; null unless it did
     */
    record RunProgress(RunPhase phase, String failureMessage) {}
}
