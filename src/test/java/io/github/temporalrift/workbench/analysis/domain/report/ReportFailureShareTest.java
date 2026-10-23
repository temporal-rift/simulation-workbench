package io.github.temporalrift.workbench.analysis.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.SyntheticRun;
import io.github.temporalrift.workbench.shared.execution.CaseState;
import io.github.temporalrift.workbench.shared.execution.FailureCode;
import io.github.temporalrift.workbench.shared.game.Faction;

class ReportFailureShareTest {

    private static final UUID RUN = new UUID(0, 1);
    private static final String DIGEST = "a".repeat(64);
    private static final List<Faction> EPW = SyntheticRun.EPW;

    private final SyntheticRun run = new SyntheticRun("base", "random");

    @Test
    void failureShareIsReportedWhereSeatsArePooledOnceForAllCodesAndOncePerCodePresent() {
        for (var seed = 1; seed <= 96; seed++) {
            run.win(String.valueOf(seed), EPW, 0);
        }
        run.failed("97", EPW, FailureCode.RUNNER_TIMEOUT)
                .failed("98", EPW, FailureCode.RUNNER_TIMEOUT)
                .failed("99", EPW, FailureCode.RUNNER_TIMEOUT)
                .failed("100", EPW, FailureCode.POLICY_EXHAUSTED);

        var report = report(AnalysisVersion.CURRENT);

        for (var cohort : report.cohorts()) {
            var shares = cohort.metrics().stream()
                    .filter(metric -> metric.name().equals("failure_share"))
                    .toList();
            if (cohort.key().seatIndex() != null) {
                assertThat(shares).as("seat cohort %s", cohort.key()).isEmpty();
                continue;
            }
            assertThat(shares)
                    .extracting(metric -> metric.dimensions().failureCode())
                    .containsExactly(null, FailureCode.RUNNER_TIMEOUT, FailureCode.POLICY_EXHAUSTED);
            assertThat(shares).allSatisfy(metric -> {
                assertThat(metric.statistic()).isEqualTo(MetricStatistic.RATE);
                assertThat(metric.unit()).isEqualTo("cases");
                assertThat(metric.denominator()).isEqualTo(100);
            });
            assertThat(shares.get(0).value()).isEqualTo(0.04);
            assertThat(shares.get(1).value()).isEqualTo(0.03);
            assertThat(shares.get(2).value()).isEqualTo(0.01);
        }
    }

    @Test
    void aCaseThatSucceededAfterARetryCountsInTheDenominatorOnly() {
        run.win("1", EPW, 0).win("2", EPW, 0).win("3", EPW, 0);

        var shares = pooled(report(AnalysisVersion.CURRENT));

        assertThat(shares).hasSize(1);
        assertThat(shares.getFirst().numerator()).isZero();
        assertThat(shares.getFirst().denominator()).isEqualTo(3);
        assertThat(shares.getFirst().value()).isZero();
    }

    @Test
    void cancelledPendingAndRunningCasesAreInNeitherCount() {
        run.win("1", EPW, 0)
                .win("2", EPW, 0)
                .failed("3", EPW, FailureCode.EXECUTION_FAILED)
                .unfinished("4", EPW, CaseState.CANCELLED)
                .unfinished("5", EPW, CaseState.PENDING)
                .unfinished("6", EPW, CaseState.RUNNING);

        var shares = pooled(report(AnalysisVersion.CURRENT));

        assertThat(shares.getFirst().numerator()).isEqualTo(1.0);
        assertThat(shares.getFirst().denominator()).isEqualTo(3);
    }

    @Test
    void aCohortWhoseEveryCaseFailedStillShowsItsFailureShareOverItsFinishedSeeds() {
        run.failed("1", EPW, FailureCode.RUNNER_TIMEOUT).failed("2", EPW, FailureCode.RUNNER_TIMEOUT);

        var pooled = report(AnalysisVersion.CURRENT).cohorts().getFirst();

        assertThat(pooled.independentBlocks()).isZero();
        var share = pooled(report(AnalysisVersion.CURRENT)).getFirst();
        assertThat(share.value()).isEqualTo(1.0);
        assertThat(share.interval()).isEqualTo(new Interval(1.0, 1.0));
    }

    @Test
    void aSeedWithOnlyFailedCasesLeavesEveryGameMetricAsVersionOneComputedIt() {
        run.win("1", EPW, 0).failed("2", EPW, FailureCode.EXECUTION_FAILED);

        var one = report(new AnalysisVersion("1", 1L));
        var two = report(AnalysisVersion.CURRENT);

        var pooledOne = one.cohorts().getFirst();
        var pooledTwo = two.cohorts().getFirst();
        assertThat(pooledTwo.independentBlocks()).isEqualTo(1).isEqualTo(pooledOne.independentBlocks());
        assertThat(pooledTwo.metrics().stream()
                        .filter(metric -> !metric.name().equals("failure_share"))
                        .toList())
                .isEqualTo(pooledOne.metrics());
        assertThat(pooledTwo.metrics())
                .filteredOn(metric -> !metric.name().equals("failure_share") && metric.status() != MetricStatus.NO_DATA)
                .allSatisfy(metric -> assertThat(metric.status()).isEqualTo(MetricStatus.INSUFFICIENT_SAMPLE));
        var share = pooled(two).getFirst();
        assertThat(share.status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(share.value()).isEqualTo(0.5);
    }

    @Test
    void versionOneReportsCarryNoFailureShareAndVersionTwoKeepsEveryVersionOneMetric() {
        for (var seed = 1; seed <= 12; seed++) {
            run.win(String.valueOf(seed), EPW, seed % 3);
        }
        run.failed("5", EPW, FailureCode.RUNNER_TIMEOUT);

        var one = report(new AnalysisVersion("1", 1L));
        var two = report(AnalysisVersion.CURRENT);

        assertThat(one.cohorts()).isNotEmpty();
        assertThat(one.cohorts().stream()
                        .flatMap(cohort -> cohort.metrics().stream())
                        .map(Metric::name))
                .isNotEmpty()
                .doesNotContain("failure_share");
        assertThat(two.cohorts()).hasSameSizeAs(one.cohorts());
        for (var index = 0; index < one.cohorts().size(); index++) {
            var twoMetrics = two.cohorts().get(index).metrics().stream()
                    .filter(metric -> !metric.name().equals("failure_share"))
                    .toList();
            assertThat(twoMetrics).isEqualTo(one.cohorts().get(index).metrics());
        }
    }

    private RunReport report(AnalysisVersion analysis) {
        return ReportCalculator.report(RUN, DIGEST, analysis, run.cases(), run.facts());
    }

    private static List<Metric> pooled(RunReport report) {
        return report.cohorts().getFirst().metrics().stream()
                .filter(metric -> metric.name().equals("failure_share"))
                .toList();
    }
}
