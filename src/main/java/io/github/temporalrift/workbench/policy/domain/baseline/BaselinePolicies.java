package io.github.temporalrift.workbench.policy.domain.baseline;

import java.util.List;
import java.util.Optional;

import io.github.temporalrift.workbench.policy.domain.decision.BotPolicy;
import io.github.temporalrift.workbench.shared.text.Sha256;

/**
 * The baseline bundles: {@code random} at {@code 1.0.0} and {@code faction-greedy} at {@code 1.0.0} and
 * {@code 2.0.0}, referred to as {@code random-v1}, {@code faction-greedy-v1} and {@code faction-greedy-v2}. Each
 * artifact digest hashes the bundle identity together with its canonical definition, so a changed definition needs a
 * new version.
 */
public final class BaselinePolicies {

    public static final String RANDOM_ID = "random";
    public static final String FACTION_GREEDY_ID = "faction-greedy";
    public static final String V1 = "1.0.0";
    public static final String V2 = "2.0.0";

    public static final PolicyBundle RANDOM_V1 = bundle(
            RANDOM_ID,
            V1,
            RandomPolicy.DEFINITION,
            "Uniform seeded draw over the canonically ordered candidates, pass or decline included.",
            new RandomPolicy());
    public static final PolicyBundle FACTION_GREEDY_V1 = bundle(
            FACTION_GREEDY_ID,
            V1,
            FactionPreferences.canonicalDefinition(),
            "Scores each candidate by its faction's affinity and target lean, then draws among the top scores.",
            new FactionGreedyPolicy());
    public static final PolicyBundle FACTION_GREEDY_V2 = bundle(
            FACTION_GREEDY_ID,
            V2,
            FactionGreedyV2Policy.DEFINITION,
            "Reads public bands, skips wasted actions, backs its own commitment and plays its faction's counters.",
            new FactionGreedyV2Policy());

    private static final List<PolicyBundle> ALL = List.of(RANDOM_V1, FACTION_GREEDY_V1, FACTION_GREEDY_V2);

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
            String id, String version, String definition, String description, BotPolicy policy) {
        return new PolicyBundle(
                id, version, Sha256.hexUtf8(id + "|" + version + "|" + definition), description, policy);
    }
}
