package io.github.temporalrift.workbench.analysis.domain.evaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import io.github.temporalrift.workbench.analysis.domain.criterion.BalanceCriterion;
import io.github.temporalrift.workbench.analysis.domain.criterion.PolicyReference;
import io.github.temporalrift.workbench.analysis.domain.evaluation.Validity.Reason;
import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.report.CohortKey;
import io.github.temporalrift.workbench.shared.execution.CaseState;
import io.github.temporalrift.workbench.shared.execution.FailureCode;
import io.github.temporalrift.workbench.shared.game.EndReason;
import io.github.temporalrift.workbench.shared.game.Faction;

/** The evidence rule of a criterion: when a run is valid, and what a confirmation sample must contain. */
final class EvidenceRules {

    private static final List<Integer> PLAYER_COUNTS = List.of(3, 4, 5);

    private EvidenceRules() {}

    /** Every reason the run cannot be evaluated as evidence, in reason-code order. */
    static Validity validity(BalanceCriterion criterion, List<AnalyzedCase> cases) {
        var evidence = criterion.evidence();
        var reasons = new ArrayList<Reason>();
        unfinished(cases).ifPresent(reasons::add);
        for (var code : List.of(FailureCode.CONTRACT_MISMATCH, FailureCode.CONFIGURATION_DRIFT)) {
            var count = cases.stream()
                    .filter(analyzedCase -> analyzedCase.failureCode() == code)
                    .count();
            if (evidence.invalidatingFailureCodes().contains(code) && count > 0) {
                reasons.add(
                        new Reason(ValidityCode.valueOf(code.name()), count + " case(s) failed with " + code.name()));
            }
        }
        reasons.addAll(executionFailedShare(criterion, cases));
        var abandoned = cases.stream()
                .filter(analyzedCase -> analyzedCase.outcome() != null)
                .filter(analyzedCase -> evidence.invalidatingEndReasons()
                        .contains(analyzedCase.outcome().endReason()))
                .count();
        if (evidence.invalidatingEndReasons().contains(EndReason.ALL_PLAYERS_ABANDONED) && abandoned > 0) {
            reasons.add(new Reason(
                    ValidityCode.ALL_PLAYERS_ABANDONED, abandoned + " game(s) ended with all players abandoned"));
        }
        return new Validity(reasons);
    }

    /** The sample a confirmation needs and how much of it the variant has. */
    static ConfirmationSample sample(BalanceCriterion criterion, List<AnalyzedCase> cases) {
        var seeds = new HashSet<String>();
        cases.forEach(analyzedCase -> seeds.add(analyzedCase.seed()));
        return new ConfirmationSample(
                criterion.evidence().confirmationMinimumSeeds(),
                seeds.size(),
                fullMatrix(criterion.policies(), seeds, cases));
    }

    private static Optional<Reason> unfinished(List<AnalyzedCase> cases) {
        var counts = new TreeMap<CaseState, Integer>();
        cases.stream()
                .filter(analyzedCase -> !analyzedCase.finished())
                .forEach(analyzedCase -> counts.merge(analyzedCase.status(), 1, Integer::sum));
        if (counts.isEmpty()) {
            return Optional.empty();
        }
        var parts = new ArrayList<String>();
        counts.forEach((status, count) -> parts.add(count + " " + status.name().toLowerCase(Locale.ROOT)));
        return Optional.of(
                new Reason(ValidityCode.UNFINISHED_CASES, "Cases did not finish: " + String.join(", ", parts)));
    }

    private static List<Reason> executionFailedShare(BalanceCriterion criterion, List<AnalyzedCase> cases) {
        var finished = new TreeMap<String, Integer>();
        var failed = new HashMap<String, Integer>();
        for (var analyzedCase : cases) {
            if (!analyzedCase.finished()) {
                continue;
            }
            var group = reference(analyzedCase) + " at " + analyzedCase.playerCount() + " players";
            finished.merge(group, 1, Integer::sum);
            if (analyzedCase.failureCode() == FailureCode.EXECUTION_FAILED) {
                failed.merge(group, 1, Integer::sum);
            }
        }
        var reasons = new ArrayList<Reason>();
        var limit = criterion.evidence().executionFailedShareLimit();
        finished.forEach((group, total) -> {
            var share = (double) failed.getOrDefault(group, 0) / total;
            if (share > limit) {
                reasons.add(new Reason(
                        ValidityCode.EXECUTION_FAILED_SHARE,
                        "EXECUTION_FAILED share of " + group + " is " + share + ", above " + limit));
            }
        });
        return reasons;
    }

    /**
     * Every policy the criterion names has, for every player count and every seed, every faction set of that size
     * with exactly one seat ordering per cyclic rotation.
     */
    private static boolean fullMatrix(Set<String> policies, Set<String> seeds, List<AnalyzedCase> cases) {
        var byCell = new HashMap<List<Object>, Map<List<Faction>, Set<List<Faction>>>>();
        for (var analyzedCase : cases) {
            byCell.computeIfAbsent(
                            List.of(reference(analyzedCase), analyzedCase.playerCount(), analyzedCase.seed()),
                            ignored -> new HashMap<>())
                    .computeIfAbsent(CohortKey.factionSetOf(analyzedCase), ignored -> new HashSet<>())
                    .add(analyzedCase.seatFactions());
        }
        for (var policy : policies) {
            for (var playerCount : PLAYER_COUNTS) {
                for (var seed : seeds) {
                    var sets = byCell.get(List.<Object>of(policy, playerCount, seed));
                    if (sets == null || !complete(sets, playerCount)) {
                        return false;
                    }
                }
            }
        }
        return !seeds.isEmpty();
    }

    private static String reference(AnalyzedCase analyzedCase) {
        return PolicyReference.of(analyzedCase.policyId(), analyzedCase.policyVersion());
    }

    private static boolean complete(Map<List<Faction>, Set<List<Faction>>> sets, int playerCount) {
        var expected = subsets(playerCount);
        if (!sets.keySet().equals(new HashSet<>(expected))) {
            return false;
        }
        return sets.values().stream().allMatch(orderings -> cyclic(orderings, playerCount));
    }

    private static boolean cyclic(Set<List<Faction>> orderings, int playerCount) {
        if (orderings.size() != playerCount) {
            return false;
        }
        var first = orderings.iterator().next();
        return orderings.stream().allMatch(ordering -> rotationOf(first, ordering));
    }

    private static boolean rotationOf(List<Faction> base, List<Faction> candidate) {
        for (var shift = 0; shift < base.size(); shift++) {
            var rotated = new ArrayList<Faction>(base.subList(shift, base.size()));
            rotated.addAll(base.subList(0, shift));
            if (rotated.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** Every set of the given size over the roster, as the matrix names a set: factions sorted by name. */
    private static List<List<Faction>> subsets(int size) {
        var roster = EnumSet.allOf(Faction.class).stream()
                .sorted(Comparator.comparing(Faction::name))
                .toList();
        var sets = new ArrayList<List<Faction>>();
        collect(roster, size, 0, new ArrayList<>(), sets);
        return sets;
    }

    private static void collect(
            List<Faction> roster, int size, int from, List<Faction> current, List<List<Faction>> sets) {
        if (current.size() == size) {
            sets.add(List.copyOf(current));
            return;
        }
        for (var index = from; index < roster.size(); index++) {
            current.add(roster.get(index));
            collect(roster, size, index + 1, current, sets);
            current.removeLast();
        }
    }
}
