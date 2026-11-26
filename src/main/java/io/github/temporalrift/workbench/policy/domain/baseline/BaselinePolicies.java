package io.github.temporalrift.workbench.policy.domain.baseline;

import java.util.List;
import java.util.Optional;

import io.github.temporalrift.workbench.policy.domain.decision.BotPolicy;
import io.github.temporalrift.workbench.shared.text.Sha256;

/**
 * The baseline bundles: {@code random}, {@code faction-greedy} and {@code faction-spread}, one of each. Each artifact
 * digest hashes the bundle id, version and behavior revision, so a changed behavior needs a new revision. A criterion
 * names a policy by its id only and resolves it to the one bundle of that id.
 */
public final class BaselinePolicies {

    public static final String RANDOM_ID = "random";
    public static final String FACTION_GREEDY_ID = "faction-greedy";
    public static final String FACTION_SPREAD_ID = "faction-spread";
    public static final String VERSION = "1.0.0";

    /**
     * The behavior revision of each bundle. Bump a bundle's revision whenever what it decides changes: its pinning
     * test in {@code BundleBehaviorPinningTest} fails until the revision and the recorded hash move together. The
     * paradox layer and the recipe are shared by {@code faction-greedy} and {@code faction-spread}, so a change there
     * bumps both.
     */
    static final String RANDOM_REVISION = "1";

    static final String FACTION_GREEDY_REVISION = "1";
    static final String FACTION_SPREAD_REVISION = "1";

    public static final PolicyBundle RANDOM = bundle(
            RANDOM_ID,
            RANDOM_REVISION,
            "Uniform seeded draw over the canonically ordered candidates, pass or decline included.",
            new RandomPolicy());
    public static final PolicyBundle FACTION_GREEDY = bundle(
            FACTION_GREEDY_ID,
            FACTION_GREEDY_REVISION,
            "Reads public bands, skips wasted actions and specials the table reports unavailable, backs its own"
                    + " commitment, plays its faction's counters and chooses paradox cards by its stake and the"
                    + " table's costs.",
            new FactionGreedyPolicy());
    public static final PolicyBundle FACTION_SPREAD = bundle(
            FACTION_SPREAD_ID,
            FACTION_SPREAD_REVISION,
            "Plays the faction-greedy recipe with its bets spread over the era's events by favorite weight, the"
                    + " Prophet naming the runner-up half the time, the Eraser annihilating a drawn favorite, no"
                    + " push toward the public pending link, and Detonate to break a leading chain or finish a"
                    + " collapse.",
            new FactionSpreadPolicy());

    private static final List<PolicyBundle> ALL = List.of(RANDOM, FACTION_GREEDY, FACTION_SPREAD);

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

    private static PolicyBundle bundle(String id, String revision, String description, BotPolicy policy) {
        return new PolicyBundle(
                id, VERSION, revision, Sha256.hexUtf8(id + "|" + VERSION + "|" + revision), description, policy);
    }
}
