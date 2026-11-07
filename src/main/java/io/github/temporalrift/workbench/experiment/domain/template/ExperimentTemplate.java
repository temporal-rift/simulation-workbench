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
            List<Table> tables,
            int concurrency,
            int caseWallTimeoutSeconds,
            int maxRejectedCandidatesPerWindow) {

        public Defaults {
            seeds = List.copyOf(seeds);
            playerCounts = List.copyOf(playerCounts);
            factionSets = factionSets.stream().map(List::copyOf).toList();
            policies = List.copyOf(policies);
            tables = List.copyOf(tables);
        }
    }

    /** A catalog policy without parameters. */
    public record Policy(String id, String version, String artifactDigest) {}

    /** A named table: the policy that plays each seat index, in seat order. */
    public record Table(String id, List<Policy> seats) {

        public Table {
            seats = List.copyOf(seats);
        }
    }
}
