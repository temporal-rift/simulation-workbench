package io.github.temporalrift.workbench.analysis.domain.evaluation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.criterion.BalanceCriterion;
import io.github.temporalrift.workbench.analysis.domain.fact.CaseFacts;
import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.statistics.BlockBootstrap;

/**
 * How far one candidate's scored lines lie from their bands, kept per resampling plan so two candidates that played
 * the same seeds can be compared plan by plan. A candidate dominates another with evidence when the paired
 * difference of their deviations never lies below 0 and lies wholly above 0 for at least one line.
 */
public final class DeviationProfile {

    private final Map<LineInstance, DeviationReplicates> deviations;

    private DeviationProfile(Map<LineInstance, DeviationReplicates> deviations) {
        this.deviations = deviations;
    }

    /** @param cases the cases of the one candidate, however many runs they came from */
    public static DeviationProfile of(
            BalanceCriterion criterion,
            List<AnalyzedCase> cases,
            Map<UUID, CaseFacts> facts,
            AnalysisVersion analysis) {
        return new DeviationProfile(LineExpansion.scoredDeviations(criterion, cases, facts, analysis));
    }

    /**
     * Whether {@code other} dominates this candidate: for every scored line the 95% paired interval of
     * {@code d(this) - d(other)} lies at or above 0, and for at least one line it lies wholly above 0.
     *
     * Candidates that did not play the same lines over the same number of seed blocks cannot be compared, and a line
     * whose interval is undefined cannot show that this candidate is no better, so neither is dominated.
     */
    public boolean dominatedBy(DeviationProfile other) {
        if (!deviations.keySet().equals(other.deviations.keySet())) {
            return false;
        }
        var strict = false;
        for (var entry : deviations.entrySet()) {
            var mine = entry.getValue();
            var theirs = other.deviations.get(entry.getKey());
            if (mine.blocks() != theirs.blocks()) {
                return false;
            }
            if (!mine.hasData() && !theirs.hasData()) {
                continue;
            }
            var interval = pairedInterval(mine, theirs);
            if (interval.isEmpty() || interval.get()[0] < 0) {
                return false;
            }
            strict |= interval.get()[0] > 0;
        }
        return strict;
    }

    private static Optional<double[]> pairedInterval(DeviationReplicates mine, DeviationReplicates theirs) {
        if (mine.hasData() != theirs.hasData() || mine.values() == null || theirs.values() == null) {
            return Optional.empty();
        }
        var difference = new double[mine.values().length];
        for (var index = 0; index < difference.length; index++) {
            difference[index] = mine.values()[index] - theirs.values()[index];
        }
        return BlockBootstrap.interval(difference);
    }
}
