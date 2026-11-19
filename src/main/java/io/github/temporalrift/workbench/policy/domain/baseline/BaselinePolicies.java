package io.github.temporalrift.workbench.policy.domain.baseline;

import java.util.List;
import java.util.Optional;

import io.github.temporalrift.workbench.policy.domain.decision.BotPolicy;
import io.github.temporalrift.workbench.shared.text.Sha256;

/**
 * The baseline bundles: {@code random} and {@code faction-greedy}, one of each. Each artifact digest hashes the bundle
 * identity together with its canonical definition, so a changed definition needs a new version. A criterion names a
 * policy by its id only and resolves it to the one bundle of that id.
 */
public final class BaselinePolicies {

    public static final String RANDOM_ID = "random";
    public static final String FACTION_GREEDY_ID = "faction-greedy";
    public static final String VERSION = "1.0.0";

    public static final PolicyBundle RANDOM = bundle(
            RANDOM_ID,
            RandomPolicy.DEFINITION,
            "Uniform seeded draw over the canonically ordered candidates, pass or decline included.",
            new RandomPolicy());
    public static final PolicyBundle FACTION_GREEDY = bundle(
            FACTION_GREEDY_ID,
            FactionGreedyPolicy.DEFINITION,
            "Reads public bands, skips wasted actions and specials the table reports unavailable, backs its own"
                    + " commitment, plays its faction's counters and chooses paradox cards by its stake and the"
                    + " table's costs.",
            new FactionGreedyPolicy());

    private static final List<PolicyBundle> ALL = List.of(RANDOM, FACTION_GREEDY);

    private BaselinePolicies() {}

    public static List<PolicyBundle> all() {
        return ALL;
    }

    public static Optional<PolicyBundle> find(String id, String version) {
        return ALL.stream()
                .filter(bundle -> bundle.id().equals(id) && bundle.version().equals(version))
                .findFirst();
    }

    /** The one bundle of a policy id. */
    public static Optional<PolicyBundle> find(String id) {
        return ALL.stream().filter(bundle -> bundle.id().equals(id)).findFirst();
    }

    private static PolicyBundle bundle(String id, String definition, String description, BotPolicy policy) {
        return new PolicyBundle(
                id, VERSION, Sha256.hexUtf8(id + "|" + VERSION + "|" + definition), description, policy);
    }
}
