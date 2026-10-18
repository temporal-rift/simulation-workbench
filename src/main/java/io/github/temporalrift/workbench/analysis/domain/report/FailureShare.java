package io.github.temporalrift.workbench.analysis.domain.report;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.game.CaseStatus;
import io.github.temporalrift.workbench.analysis.domain.game.FailureCode;
import io.github.temporalrift.workbench.analysis.domain.statistics.BlockSeries;

/**
 * The per-block tallies of {@code failure_share}: cases that finished {@code FAILED} (with a code, or with any) over
 * cases that finished {@code SUCCEEDED} or {@code FAILED}. It counts cases, not games, because a failed case leaves
 * every game metric and a stalled configuration would otherwise look healthy.
 */
public final class FailureShare {

    public static final String NAME = "failure_share";
    public static final String UNIT = "cases";

    private final BlockSeries all;
    private final Map<FailureCode, BlockSeries> byCode;

    private FailureShare(BlockSeries all, Map<FailureCode, BlockSeries> byCode) {
        this.all = all;
        this.byCode = byCode;
    }

    /**
     * @param seeds the cohort's blocks in block order
     * @param finished the cohort's cases that finished {@code SUCCEEDED} or {@code FAILED}
     */
    public static FailureShare collect(List<String> seeds, List<AnalyzedCase> finished) {
        var all = new BlockSeries(seeds.size());
        var byCode = new EnumMap<FailureCode, BlockSeries>(FailureCode.class);
        for (var code : FailureCode.values()) {
            byCode.put(code, new BlockSeries(seeds.size()));
        }
        var blocks = new HashMap<String, Integer>();
        seeds.forEach(seed -> blocks.put(seed, blocks.size()));
        for (var analyzedCase : finished) {
            var block = blocks.get(analyzedCase.seed());
            var failed = analyzedCase.failureCode();
            all.addRatio(block, analyzedCase.status() == CaseStatus.FAILED ? 1 : 0, 1, 0);
            byCode.forEach((code, series) -> series.addRatio(block, code == failed ? 1 : 0, 1, 0));
        }
        return new FailureShare(all, byCode);
    }

    /** The share over every code. */
    public BlockSeries all() {
        return all;
    }

    public BlockSeries of(FailureCode code) {
        return byCode.get(code);
    }

    /** The metrics of a cohort: all codes, then each given code in code order. */
    public static List<MetricHeader> headers(Set<FailureCode> codes) {
        var headers = new ArrayList<MetricHeader>();
        headers.add(header(Dimensions.NONE));
        for (var code : FailureCode.values()) {
            if (codes.contains(code)) {
                headers.add(header(Dimensions.of(code)));
            }
        }
        return List.copyOf(headers);
    }

    private static MetricHeader header(Dimensions dimensions) {
        return new MetricHeader(NAME, MetricStatistic.RATE, dimensions, UNIT);
    }
}
