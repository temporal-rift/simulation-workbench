package io.github.temporalrift.workbench.analysis.domain.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.SyntheticRun;
import io.github.temporalrift.workbench.analysis.domain.TestCriteria;

class DeviationProfileTest {

    private static final String GREEDY = "faction-greedy-v1";
    private static final int SEEDS = 12;

    @Test
    void aCandidateWhoseEveryLineIsWorseAndOneIsWhollyWorseIsDominated() {
        var rotating = profileOf(rotatingWinners("a"));
        var erasersAlways = profileOf(erasersAlwaysWin("b"));

        assertThat(erasersAlways.dominatedBy(rotating)).isTrue();
    }

    @Test
    void theBetterCandidateIsNotDominatedByTheWorse() {
        var rotating = profileOf(rotatingWinners("a"));
        var erasersAlways = profileOf(erasersAlwaysWin("b"));

        assertThat(rotating.dominatedBy(erasersAlways)).isFalse();
    }

    @Test
    void candidatesWithTheSameResultsDoNotDominateEachOther() {
        var first = profileOf(rotatingWinners("a"));
        var second = profileOf(rotatingWinners("b"));

        assertThat(first.dominatedBy(second)).isFalse();
        assertThat(second.dominatedBy(first)).isFalse();
    }

    @Test
    void candidatesThatResampledADifferentNumberOfSeedBlocksCannotBeCompared() {
        var rotating = profileOf(rotatingWinners("a"));
        var shorter = new SyntheticRun("b", GREEDY);
        for (var seed = 1; seed <= SEEDS - 1; seed++) {
            shorter.win(String.valueOf(seed), SyntheticRun.EPW, 0);
        }

        assertThat(profileOf(shorter).dominatedBy(rotating)).isFalse();
    }

    @Test
    void aSingleSeedBlockHasNoIntervalAndDominatesNothing() {
        var one = new SyntheticRun("a", GREEDY).win("1", SyntheticRun.EPW, 1);
        var other = new SyntheticRun("b", GREEDY).win("1", SyntheticRun.EPW, 0);

        assertThat(profileOf(other).dominatedBy(profileOf(one))).isFalse();
    }

    private static SyntheticRun rotatingWinners(String variant) {
        var run = new SyntheticRun(variant, GREEDY);
        for (var seed = 1; seed <= SEEDS; seed++) {
            run.win(String.valueOf(seed), SyntheticRun.EPW, seed % 3);
        }
        return run;
    }

    private static SyntheticRun erasersAlwaysWin(String variant) {
        var run = new SyntheticRun(variant, GREEDY);
        for (var seed = 1; seed <= SEEDS; seed++) {
            run.win(String.valueOf(seed), SyntheticRun.EPW, 0);
        }
        return run;
    }

    private static DeviationProfile profileOf(SyntheticRun run) {
        return DeviationProfile.of(TestCriteria.v1(), run.cases(), run.facts(), AnalysisVersion.CURRENT);
    }
}
