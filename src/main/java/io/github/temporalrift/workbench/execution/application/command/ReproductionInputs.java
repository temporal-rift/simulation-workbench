package io.github.temporalrift.workbench.execution.application.command;

import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.evidence.DecisionTranscript;
import io.github.temporalrift.workbench.execution.domain.port.out.ExperimentSource;
import io.github.temporalrift.workbench.execution.domain.reproduction.ManifestMismatchException;
import io.github.temporalrift.workbench.execution.domain.run.AttemptState;
import io.github.temporalrift.workbench.execution.domain.run.LogicalCase;
import io.github.temporalrift.workbench.execution.domain.run.RunNotFoundException;

/**
 * Everything an exact reproduction needs, each part checked against what the case was pinned to: the
 * frozen manifest still has the digest the case ran under, the retained artifacts still match their content
 * addresses, and the transcript belongs to the result the case produced.
 */
record ReproductionInputs(
        LogicalCase logicalCase,
        ExperimentSource.Plan plan,
        DecisionTranscript transcript,
        UUID originalGameId,
        String originalLaneId) {

    static ReproductionInputs verify(UUID runId, UUID caseId, ReproductionSources sources) {
        var runs = sources.runs();
        var experiments = sources.experiments();
        var evidence = sources.evidence();
        var logicalCase = runs.findCase(runId, caseId).orElseThrow(() -> new RunNotFoundException("Case", caseId));
        var run = runs.find(runId).orElseThrow(() -> new RunNotFoundException("Run", runId));
        var result = logicalCase.result();
        if (result == null) {
            throw new ManifestMismatchException("The case has no saved result to reproduce");
        }
        var pinned = evidence.pinned(caseId)
                .orElseThrow(() -> new ManifestMismatchException("The case has no retained transcript to reproduce"));
        var plan = experiments
                .plan(run.experimentId())
                .orElseThrow(() -> new ManifestMismatchException("The frozen experiment is unavailable"));
        if (!pinned.manifestDigest().equals(plan.manifestDigest())) {
            throw new ManifestMismatchException("The frozen manifest differs from the one the case ran under");
        }
        if (!pinned.resultDigest().equals(result.semanticDigest())) {
            throw new ManifestMismatchException("The retained transcript belongs to a different result");
        }
        var played = runs.attemptsOf(caseId).stream()
                .filter(attempt -> attempt.state() == AttemptState.SUCCEEDED && attempt.gameId() != null)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new ManifestMismatchException("The case has no succeeded game on record"));
        if (played.laneId() == null) {
            throw new ManifestMismatchException("The lane that played the case is not on record");
        }
        try {
            return new ReproductionInputs(
                    logicalCase, plan, DecisionTranscript.parse(pinned.transcript()), played.gameId(), played.laneId());
        } catch (IllegalArgumentException e) {
            throw new ManifestMismatchException("The retained transcript cannot be read: " + e.getMessage());
        }
    }
}
