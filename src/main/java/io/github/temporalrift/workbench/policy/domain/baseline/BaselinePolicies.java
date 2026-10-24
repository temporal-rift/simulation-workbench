package io.github.temporalrift.workbench.policy.domain.baseline;

import java.util.List;
import java.util.Optional;

import io.github.temporalrift.workbench.shared.text.Sha256;

/**
 * The two baseline bundles. Ids are {@code random} and {@code faction-greedy} at version
 * {@code 1.0.0}, referred to as {@code random-v1} and {@code faction-greedy-v1}. Each artifact
 * digest hashes the bundle identity together with its canonical definition.
 */
public final class BaselinePolicies {

    public static final String RANDOM_ID = "random";
    public static final String FACTION_GREEDY_ID = "faction-greedy";
    public static final String VERSION = "1.0.0";

    public static final PolicyBundle RANDOM_V1 = bundle(
            RANDOM_ID,
            RandomPolicy.DEFINITION,
            "Uniform seeded draw over the canonically ordered candidates, pass or decline included.",
            new RandomPolicy());
    public static final PolicyBundle FACTION_GREEDY_V1 = bundle(
            FACTION_GREEDY_ID,
            FactionPreferences.canonicalDefinition(),
            "Scores each candidate by its faction's affinity and target lean, then draws among the top scores.",
            new FactionGreedyPolicy());

    private static final List<PolicyBundle> ALL = List.of(RANDOM_V1, FACTION_GREEDY_V1);

    private BaselinePolicies() {}

    public static List<PolicyBundle> all() {
        return ALL;
    }

    public static Optional<PolicyBundle> find(String id, String version) {
        return ALL.stream()
                .filter(bundle -> bundle.id().equals(id) && bundle.version().equals(version))
                .findFirst();
    }

    private static PolicyBundle bundle(
            String id,
            String definition,
            String description,
            io.github.temporalrift.workbench.policy.domain.decision.BotPolicy policy) {
        return new PolicyBundle(
                id, VERSION, Sha256.hexUtf8(id + "|" + VERSION + "|" + definition), description, policy);
    }
}
