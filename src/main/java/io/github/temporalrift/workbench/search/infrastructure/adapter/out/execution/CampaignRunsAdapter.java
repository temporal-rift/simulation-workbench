package io.github.temporalrift.workbench.search.infrastructure.adapter.out.execution;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.ExecutionRefusedException;
import io.github.temporalrift.workbench.execution.RunCase;
import io.github.temporalrift.workbench.execution.RunCatalog;
import io.github.temporalrift.workbench.execution.RunStarter;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignPlayRefusedException;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRuns;

/** Plays the waves of a campaign as runs of the execution module. */
public class CampaignRunsAdapter implements CampaignRuns {

    private final RunStarter runs;
    private final RunCatalog catalog;

    public CampaignRunsAdapter(RunStarter runs, RunCatalog catalog) {
        this.runs = runs;
        this.catalog = catalog;
    }

    @Override
    public UUID start(UUID experimentId, UUID idempotencyKey) {
        try {
            return runs.start(experimentId, idempotencyKey);
        } catch (ExecutionRefusedException e) {
            throw new CampaignPlayRefusedException(e.getMessage(), e);
        }
    }

    @Override
    public UUID startHeld(UUID experimentId, UUID idempotencyKey) {
        try {
            return runs.startHeld(experimentId, idempotencyKey);
        } catch (ExecutionRefusedException e) {
            throw new CampaignPlayRefusedException(e.getMessage(), e);
        }
    }

    @Override
    public Optional<RunProgress> progress(UUID runId) {
        return catalog.run(runId).map(run -> new RunProgress(run.phase(), run.failureMessage()));
    }

    @Override
    public void setConcurrency(UUID runId, int concurrency) {
        try {
            runs.setConcurrency(runId, concurrency);
        } catch (ExecutionRefusedException _) {
            // A run that is over or gone has nothing left to hold back or allow.
        }
    }

    @Override
    public void resume(UUID runId) {
        try {
            runs.resume(runId);
        } catch (ExecutionRefusedException _) {
            // A run that is not interrupted needs no resuming.
        }
    }

    @Override
    public void cancel(UUID runId) {
        try {
            runs.cancel(runId);
        } catch (ExecutionRefusedException _) {
            // A run that is gone has nothing to cancel.
        }
    }

    @Override
    public Set<String> finishedSeeds(UUID runId, String variantLabel) {
        var over = new LinkedHashMap<String, Boolean>();
        for (RunCase runCase : catalog.cases(runId)) {
            if (runCase.variantLabel().equals(variantLabel)) {
                over.merge(runCase.seed(), runCase.state().isFinal(), Boolean::logicalAnd);
            }
        }
        var finished = new HashSet<String>();
        over.forEach((seed, done) -> {
            if (Boolean.TRUE.equals(done)) {
                finished.add(seed);
            }
        });
        return finished;
    }
}
