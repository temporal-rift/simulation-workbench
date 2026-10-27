package io.github.temporalrift.workbench.fidelity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class FidelityCountsTest {

    private static List<FidelityCaseResult> results(int matched, int diverged, int unreproducible, int pending) {
        var all = new ArrayList<FidelityCaseResult>();
        all.addAll(Collections.nCopies(matched, FidelityCaseResult.MATCH));
        all.addAll(Collections.nCopies(diverged, FidelityCaseResult.DIVERGED));
        all.addAll(Collections.nCopies(unreproducible, FidelityCaseResult.UNREPRODUCIBLE));
        all.addAll(Collections.nCopies(pending, FidelityCaseResult.PENDING));
        return all;
    }

    @Test
    void everyCaseMatchedPasses() {
        var counts = FidelityCounts.of(results(330, 0, 0, 0));

        assertThat(counts).isEqualTo(new FidelityCounts(330, 330, 0, 0, 0));
        assertThat(counts.passes()).isTrue();
        assertThat(counts.settled()).isTrue();
    }

    @Test
    void oneDivergenceFailsWithNoTolerance() {
        var counts = FidelityCounts.of(results(329, 1, 0, 0));

        assertThat(counts.passes()).isFalse();
        assertThat(counts.settled()).isTrue();
    }

    @Test
    void oneUnreproducibleCaseFails() {
        assertThat(FidelityCounts.of(results(329, 0, 1, 0)).passes()).isFalse();
    }

    @Test
    void aCheckWithoutCasesNeverPasses() {
        var counts = FidelityCounts.of(List.of());

        assertThat(counts.passes()).isFalse();
        assertThat(counts.settled()).isTrue();
    }

    @Test
    void aPendingCaseIsNotSettled() {
        var counts = FidelityCounts.of(results(329, 0, 0, 1));

        assertThat(counts.settled()).isFalse();
        assertThat(counts.passes()).isFalse();
    }
}
