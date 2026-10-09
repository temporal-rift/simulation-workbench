package io.github.temporalrift.workbench.analysis.domain.report;

import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;

import io.github.temporalrift.workbench.analysis.domain.fact.CaseFacts;
import io.github.temporalrift.workbench.analysis.domain.fact.SeatFacts;

/** A metric of the catalog and how one eligible game contributes to it within a cohort's seats. */
public record MetricDefinition(
        String name, MetricStatistic statistic, Dimensions dimensions, String unit, Measure measure) {

    public MetricDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(statistic, "statistic");
        Objects.requireNonNull(dimensions, "dimensions");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(measure, "measure");
    }

    /** A game's contribution to a RATE or MEAN. */
    public record Tally(long numerator, long denominator, long unknown) {

        public static Tally of(long numerator, long denominator) {
            return new Tally(numerator, denominator, 0);
        }
    }

    /** How a game contributes: as a ratio part, or as observed values of a quantile. */
    public sealed interface Measure {

        record Ratio(RatioFunction function) implements Measure {}

        record Values(ValueFunction function) implements Measure {}
    }

    @FunctionalInterface
    public interface RatioFunction {

        Tally of(CaseFacts facts, List<SeatFacts> seats);
    }

    @FunctionalInterface
    public interface ValueFunction {

        void values(CaseFacts facts, List<SeatFacts> seats, IntConsumer sink);
    }
}
