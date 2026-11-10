package io.github.temporalrift.workbench.search.domain.round;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.github.temporalrift.workbench.search.domain.campaign.SearchAlgorithm;
import io.github.temporalrift.workbench.search.domain.log.LogEntry;
import io.github.temporalrift.workbench.search.domain.log.LogEntry.Decision;
import io.github.temporalrift.workbench.search.domain.log.LogEntry.Evaluation;
import io.github.temporalrift.workbench.search.domain.log.LogEntry.Evidence;
import io.github.temporalrift.workbench.search.domain.log.LogEntry.Reason;

/**
 * Takes the decisions of a finished round and of the end of the search. Every decision is a pure function of the
 * assessments, taken in candidate order, so neither the order games finished in nor how many ran at once can change
 * the log.
 */
public final class RoundDecider {

    /** How many consecutive rounds the short list must stay the same before the search stops. */
    public static final int STABLE_ROUNDS = 2;

    private static final Comparator<Assessment> BEST_FIRST = Comparator.comparing(
                    (Assessment assessment) -> assessment.verdict() == Assessment.Verdict.INVALID)
            .thenComparing(assessment -> assessment.score() == null ? Double.MAX_VALUE : assessment.score())
            .thenComparing(assessment -> assessment.candidate().id());

    /** Smallest gate miss first, then the better score, then candidate id. */
    private static final Comparator<Assessment> CLOSEST_FIRST = Comparator.comparing(
                    (Assessment assessment) -> assessment.gateMiss() == null ? Double.MAX_VALUE : assessment.gateMiss())
            .thenComparing(assessment -> assessment.score() == null ? Double.MAX_VALUE : assessment.score())
            .thenComparing(assessment -> assessment.candidate().id());

    private RoundDecider() {}

    /**
     * The decisions of a round, one entry per candidate in candidate order, and the survivors.
     *
     * Version 1: a candidate whose gate fails is discarded with the failing lines. Of the others, one that another
     * candidate dominates with evidence is discarded, naming the first dominator in candidate order. Of those that
     * remain, the best {@code 1/eta}, at least one, are kept and the rest discarded as outside the top fraction. A
     * score alone never discards a candidate inside the top fraction. Version 2 ranks instead of discarding for a
     * failing gate; see {@link #decideByRank}.
     *
     * @param assessments every candidate that played the round
     * @param nextSequence the sequence number of the first entry
     */
    public static Outcome decide(List<Assessment> assessments, SearchAlgorithm algorithm, int round, int nextSequence) {
        return algorithm.version() == SearchAlgorithm.Version.SUCCESSIVE_HALVING_V2
                ? decideByRank(assessments, algorithm, round, nextSequence)
                : decideByGate(assessments, algorithm, round, nextSequence);
    }

    /**
     * Version 2: a failing gate ranks a candidate instead of discarding it. The candidates are ranked in three groups:
     * those that pass every gate, by score, after discarding any another of them dominates; then those that fail a
     * gate, by gate miss, then score; then those whose evaluation is invalid. Remaining ties go by candidate id. The
     * first {@code ⌈n / eta⌉} of the {@code n} candidates are kept, so the plan's size never depends on how many
     * fail. One not kept is discarded with its failing lines when it fails a gate, else as outside the top fraction.
     */
    private static Outcome decideByRank(
            List<Assessment> assessments, SearchAlgorithm algorithm, int round, int nextSequence) {
        var ordered = byCandidateId(assessments);
        var passingIds = ordered.stream()
                .filter(RoundDecider::validAndPassing)
                .map(assessment -> assessment.candidate().id())
                .toList();
        var ranked = new ArrayList<Assessment>();
        ordered.stream()
                .filter(assessment -> validAndPassing(assessment) && firstDominator(assessment, passingIds) == null)
                .sorted(BEST_FIRST)
                .forEach(ranked::add);
        ordered.stream()
                .filter(assessment ->
                        assessment.verdict() != Assessment.Verdict.INVALID && !assessment.passesEveryGate())
                .sorted(CLOSEST_FIRST)
                .forEach(ranked::add);
        ordered.stream()
                .filter(assessment -> assessment.verdict() == Assessment.Verdict.INVALID)
                .forEach(ranked::add);
        var kept = ranked.stream().limit(algorithm.keptOf(ordered.size())).toList();
        var entries = new ArrayList<LogEntry>();
        for (var assessment : ordered) {
            entries.add(entry(
                    assessment, round, nextSequence + entries.size(), rankedDecisionOf(assessment, kept, passingIds)));
        }
        var shortList = kept.stream()
                .filter(RoundDecider::validAndPassing)
                .limit(algorithm.shortListSize())
                .map(assessment -> assessment.candidate().id())
                .toList();
        return new Outcome(List.copyOf(entries), kept, shortList);
    }

    /**
     * The entries that end a version 2 plan, after its last round is decided: each kept candidate that passes every
     * gate is short-listed in rank order, and each that fails a gate is discarded as not meeting the gates, with its
     * failing lines. One whose evaluation is invalid has no gate result, so it is discarded as outside the top
     * fraction, as is a passing one beyond the short list's size.
     *
     * @param kept the candidates the last round kept, best first
     */
    public static List<LogEntry> concludePlan(
            List<Assessment> kept, SearchAlgorithm algorithm, int round, int nextSequence) {
        var entries = new ArrayList<LogEntry>();
        var listed = 0;
        for (var assessment : kept) {
            Verdict verdict;
            if (validAndPassing(assessment) && listed < algorithm.shortListSize()) {
                verdict = new Verdict(Decision.SHORT_LISTED, null, null);
                listed++;
            } else if (assessment.verdict() != Assessment.Verdict.INVALID && !assessment.passesEveryGate()) {
                verdict = new Verdict(
                        Decision.DISCARDED,
                        Reason.GATES_NOT_MET,
                        Evidence.failingLines(assessment.failingGateLineIds()));
            } else {
                verdict = new Verdict(Decision.DISCARDED, Reason.NOT_IN_TOP_FRACTION, null);
            }
            entries.add(entry(assessment, round, nextSequence + entries.size(), verdict));
        }
        return List.copyOf(entries);
    }

    private static boolean validAndPassing(Assessment assessment) {
        return assessment.verdict() != Assessment.Verdict.INVALID && assessment.passesEveryGate();
    }

    /** The first candidate, in id order, among {@code passingIds} that dominates this one; null when none does. */
    private static String firstDominator(Assessment assessment, List<String> passingIds) {
        return assessment.dominators().stream()
                .filter(passingIds::contains)
                .findFirst()
                .orElse(null);
    }

    private static Verdict rankedDecisionOf(Assessment assessment, List<Assessment> kept, List<String> passingIds) {
        if (kept.contains(assessment)) {
            return new Verdict(Decision.KEPT, null, null);
        }
        if (assessment.verdict() != Assessment.Verdict.INVALID && !assessment.passesEveryGate()) {
            return new Verdict(
                    Decision.DISCARDED, Reason.GATE_FAILS, Evidence.failingLines(assessment.failingGateLineIds()));
        }
        var dominator = validAndPassing(assessment) ? firstDominator(assessment, passingIds) : null;
        if (dominator != null) {
            return new Verdict(Decision.DISCARDED, Reason.DOMINATED, Evidence.dominatedBy(dominator));
        }
        return new Verdict(Decision.DISCARDED, Reason.NOT_IN_TOP_FRACTION, null);
    }

    private static List<Assessment> byCandidateId(List<Assessment> assessments) {
        return assessments.stream()
                .sorted(Comparator.comparing(
                        assessment -> assessment.candidate().id()))
                .toList();
    }

    /** Version 1: a failing gate discards; the rest are ranked by score after dominance. */
    private static Outcome decideByGate(
            List<Assessment> assessments, SearchAlgorithm algorithm, int round, int nextSequence) {
        var ordered = byCandidateId(assessments);
        var remaining = ordered.stream()
                .filter(assessment -> assessment.verdict() != Assessment.Verdict.REJECTED
                        && assessment.dominators().isEmpty())
                .sorted(BEST_FIRST)
                .toList();
        var keptCount = Math.max(1, Math.ceilDiv(remaining.size(), algorithm.eta()));
        var kept = remaining.stream().limit(keptCount).toList();
        var entries = new ArrayList<LogEntry>();
        for (var assessment : ordered) {
            entries.add(entry(assessment, round, nextSequence + entries.size(), decisionOf(assessment, kept)));
        }
        return new Outcome(List.copyOf(entries), kept, shortListOf(kept, algorithm));
    }

    /**
     * The entries that close the search: the best survivors short-listed, the other survivors discarded for the
     * reason the search stopped, and the candidates of a round that was never decided discarded for lack of budget.
     *
     * @param survivors the survivors of the last decided round, best first
     * @param undecided candidates of a round still in play when the budget ran out, which are not evaluated
     * @param stopReason why the search stopped
     */
    public static List<LogEntry> conclude(
            List<Assessment> survivors,
            List<Assessment> undecided,
            SearchAlgorithm algorithm,
            StopReason stopReason,
            int round,
            int nextSequence) {
        var entries = new ArrayList<LogEntry>();
        var shortListed = shortListOf(survivors, algorithm);
        for (var assessment : survivors) {
            var listed = shortListed.contains(assessment.candidate().id());
            var decision = listed
                    ? new Verdict(Decision.SHORT_LISTED, null, null)
                    : new Verdict(Decision.DISCARDED, stopReason.reasonForSurvivors(), null);
            entries.add(entry(assessment, round, nextSequence + entries.size(), decision));
        }
        undecided.stream()
                .sorted(Comparator.comparing(
                        assessment -> assessment.candidate().id()))
                .forEach(assessment -> entries.add(unevaluated(
                        assessment,
                        round,
                        nextSequence + entries.size(),
                        new Verdict(Decision.DISCARDED, Reason.BUDGET_EXHAUSTED, null))));
        return List.copyOf(entries);
    }

    /** The best {@code shortListSize} of the survivors, by candidate id in rank order. */
    public static List<String> shortListOf(List<Assessment> survivors, SearchAlgorithm algorithm) {
        return survivors.stream()
                .limit(algorithm.shortListSize())
                .map(assessment -> assessment.candidate().id())
                .toList();
    }

    /**
     * Whether the short list stayed the same through the last {@link #STABLE_ROUNDS} rounds, that is, whether the
     * lists after the last {@code STABLE_ROUNDS + 1} rounds are all equal.
     *
     * @param shortListsByRound the short list after each decided round, in round order
     */
    public static boolean stable(List<List<String>> shortListsByRound) {
        var count = shortListsByRound.size();
        if (count <= STABLE_ROUNDS) {
            return false;
        }
        var last = shortListsByRound.getLast();
        return shortListsByRound.subList(count - STABLE_ROUNDS - 1, count).stream()
                .allMatch(last::equals);
    }

    private static Verdict decisionOf(Assessment assessment, List<Assessment> kept) {
        if (assessment.verdict() == Assessment.Verdict.REJECTED) {
            return new Verdict(
                    Decision.DISCARDED, Reason.GATE_FAILS, Evidence.failingLines(assessment.failingGateLineIds()));
        }
        if (!assessment.dominators().isEmpty()) {
            return new Verdict(
                    Decision.DISCARDED,
                    Reason.DOMINATED,
                    Evidence.dominatedBy(assessment.dominators().getFirst()));
        }
        return kept.contains(assessment)
                ? new Verdict(Decision.KEPT, null, null)
                : new Verdict(Decision.DISCARDED, Reason.NOT_IN_TOP_FRACTION, null);
    }

    private static LogEntry entry(Assessment assessment, int round, int sequence, Verdict verdict) {
        return build(
                assessment,
                round,
                sequence,
                verdict,
                new Evaluation(assessment.verdict().name(), assessment.score(), assessment.gateMiss()));
    }

    private static LogEntry unevaluated(Assessment assessment, int round, int sequence, Verdict verdict) {
        return build(assessment, round, sequence, verdict, null);
    }

    private static LogEntry build(
            Assessment assessment, int round, int sequence, Verdict verdict, Evaluation evaluation) {
        var candidate = assessment.candidate();
        return new LogEntry(
                sequence,
                round,
                candidate.id(),
                candidate.overrides(),
                candidate.effectiveRulesDigest(),
                assessment.seeds(),
                assessment.gamesSpent(),
                candidate.redraws(),
                evaluation,
                verdict.decision(),
                verdict.reason(),
                verdict.evidence());
    }

    /** What was decided, why and on what evidence. */
    private record Verdict(Decision decision, Reason reason, Evidence evidence) {}

    /** The decisions of one round.
     *
     * @param entries one per candidate, in candidate order
     * @param survivors the kept candidates, best first
     * @param shortList the best {@code shortListSize} survivors
     */
    public record Outcome(List<LogEntry> entries, List<Assessment> survivors, List<String> shortList) {}

    /** Why the search stopped. */
    public enum StopReason {
        /** The budget ran out. */
        BUDGET_EXHAUSTED(Reason.BUDGET_EXHAUSTED),
        /** The short list stayed the same, or nothing remained to improve it. */
        SHORT_LIST_STABLE(Reason.NOT_IN_TOP_FRACTION);

        private final Reason survivorsReason;

        StopReason(Reason survivorsReason) {
            this.survivorsReason = survivorsReason;
        }

        /** Why the survivors that did not make the short list are discarded. */
        Reason reasonForSurvivors() {
            return survivorsReason;
        }
    }
}
