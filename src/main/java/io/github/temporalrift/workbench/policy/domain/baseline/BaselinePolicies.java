package io.github.temporalrift.workbench.policy.domain.baseline;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

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
        return new PolicyBundle(id, VERSION, sha256Hex(id + "|" + VERSION + "|" + definition), description, policy);
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
