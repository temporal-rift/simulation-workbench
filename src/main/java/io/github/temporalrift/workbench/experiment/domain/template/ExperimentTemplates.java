package io.github.temporalrift.workbench.experiment.domain.template;

import java.util.ArrayList;
import java.util.List;

import io.github.temporalrift.workbench.experiment.domain.ExperimentValidator;
import io.github.temporalrift.workbench.experiment.domain.port.out.PolicyArtifacts;

/** The templates the workbench offers, in template id order. */
public final class ExperimentTemplates {

    private static final List<String> FACTIONS = List.of("ERASERS", "PROPHETS", "REVISIONISTS", "WEAVERS", "ACTIVISTS");
    private static final List<Integer> PLAYER_COUNTS = List.of(3, 4, 5);
    private static final List<Integer> EVERY_SEAT = List.of(0, 1, 2, 3, 4);
    private static final String BASELINE_VERSION = "1.0.0";

    private ExperimentTemplates() {}

    /** Both templates, proposing one concurrent case per lane within the manifest's concurrency bound. */
    public static List<ExperimentTemplate> builtIn(int laneCount, TemplateBounds bounds, PolicyArtifacts policies) {
        int concurrency = Math.clamp(laneCount, 1, ExperimentValidator.MAX_CONCURRENCY);
        return List.of(
                new ExperimentTemplate(
                        "baseline-policy-comparison",
                        "Baseline policy comparison",
                        "Compares the random and faction-greedy baseline policies, each on every seat, under one"
                                + " rules and content bundle.",
                        1,
                        defaults(
                                List.of(policy("random", policies), policy("faction-greedy", policies)),
                                concurrency,
                                bounds)),
                new ExperimentTemplate(
                        "rules-variant-comparison",
                        "Rules variant comparison",
                        "Compares two rules and content bundles with the random baseline policy on every seat.",
                        2,
                        defaults(List.of(policy("random", policies)), concurrency, bounds)));
    }

    /** Every set of distinct factions of each player count, smallest first, in faction order. */
    static List<List<String>> everyFactionSet() {
        var sets = new ArrayList<List<String>>();
        PLAYER_COUNTS.forEach(size -> collectCombinations(size, 0, new ArrayList<>(), sets));
        return List.copyOf(sets);
    }

    private static void collectCombinations(int size, int from, List<String> chosen, List<List<String>> sets) {
        if (chosen.size() == size) {
            sets.add(List.copyOf(chosen));
            return;
        }
        for (int index = from; index < FACTIONS.size(); index++) {
            chosen.add(FACTIONS.get(index));
            collectCombinations(size, index + 1, chosen, sets);
            chosen.removeLast();
        }
    }

    private static ExperimentTemplate.Defaults defaults(
            List<ExperimentTemplate.Policy> policies, int concurrency, TemplateBounds bounds) {
        return new ExperimentTemplate.Defaults(
                List.of("42"),
                PLAYER_COUNTS,
                everyFactionSet(),
                "CYCLIC",
                "LOGICAL",
                policies,
                concurrency,
                bounds.caseWallTimeoutSeconds(),
                bounds.maxRejectedCandidatesPerWindow());
    }

    private static ExperimentTemplate.Policy policy(String id, PolicyArtifacts policies) {
        var digest = policies.artifactDigest(id, BASELINE_VERSION)
                .orElseThrow(() -> new IllegalStateException(
                        "baseline policy " + id + "@" + BASELINE_VERSION + " is not defined"));
        return new ExperimentTemplate.Policy(id, BASELINE_VERSION, digest, EVERY_SEAT);
    }
}
