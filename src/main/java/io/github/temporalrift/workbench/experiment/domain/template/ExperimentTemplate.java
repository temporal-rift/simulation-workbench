package io.github.temporalrift.workbench.experiment.domain.template;

import java.util.List;

/**
 * A named starting point for a common experiment shape: complete defaults for every manifest field that does not
 * identify a bundle.
 *
 * @param variantCount the variants the template needs, each from a distinct bundle
 */
public record ExperimentTemplate(
        String templateId, String name, String description, int variantCount, Defaults defaults) {

    /** Defaults with exactly the shape and constraints the manifest gives these fields. */
    public record Defaults(
            List<String> seeds,
            List<Integer> playerCounts,
            List<List<String>> factionSets,
            String seatRotationMode,
            String timingMode,
            List<Policy> policies,
            int concurrency,
            int caseWallTimeoutSeconds,
            int maxRejectedCandidatesPerWindow) {

        public Defaults {
            seeds = List.copyOf(seeds);
            playerCounts = List.copyOf(playerCounts);
            factionSets = factionSets.stream().map(List::copyOf).toList();
            policies = List.copyOf(policies);
        }
    }

    /** A catalog policy without parameters, playing the listed seats. */
    public record Policy(String id, String version, String artifactDigest, List<Integer> seats) {

        public Policy {
            seats = List.copyOf(seats);
        }
    }
}
