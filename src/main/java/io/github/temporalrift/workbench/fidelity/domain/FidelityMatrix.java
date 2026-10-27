package io.github.temporalrift.workbench.fidelity.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * The case matrix every check plays: player counts 3, 4 and 5, every faction set under {@code CYCLIC} seat rotation,
 * and both baseline policies, each on every seat, for each of the three variants.
 */
public final class FidelityMatrix {

    public static final List<Integer> PLAYER_COUNTS = List.of(3, 4, 5);

    /** The seats a case of the largest player count uses. */
    public static final int MAX_SEATS = 5;

    public static final String POLICY_VERSION = "1.0.0";
    public static final String FACTION_GREEDY = "faction-greedy";
    public static final String RANDOM = "random";

    /** The policy ids in the order the contract lists cases. */
    public static final List<String> POLICY_IDS = List.of(FACTION_GREEDY, RANDOM);

    private static final Comparator<List<Faction>> BY_FACTION_NAMES = (left, right) -> {
        for (var index = 0; index < Math.min(left.size(), right.size()); index++) {
            var order = left.get(index).name().compareTo(right.get(index).name());
            if (order != 0) {
                return order;
            }
        }
        return Integer.compare(left.size(), right.size());
    };

    /** Cases in the order the contract lists them: variant, player count, faction set, seat rotation, policy id. */
    public static final Comparator<FidelityCase> ORDER = Comparator.comparing(FidelityCase::variant)
            .thenComparingInt(FidelityCase::playerCount)
            .thenComparing(FidelityCase::factionSet, BY_FACTION_NAMES)
            .thenComparingInt(FidelityCase::seatRotation)
            .thenComparing(FidelityCase::policyId);

    private FidelityMatrix() {}

    /** Every set of distinct factions of each player count, smallest first, each ordered by faction name. */
    public static List<List<Faction>> factionSets() {
        var names = Arrays.stream(Faction.values())
                .sorted(Comparator.comparing(Faction::name))
                .toList();
        var sets = new ArrayList<List<Faction>>();
        PLAYER_COUNTS.forEach(size -> collect(names, size, 0, new ArrayList<>(), sets));
        return List.copyOf(sets);
    }

    /** How many cases a check plays: 55 per policy and variant. */
    public static int caseCount() {
        return factionSets().stream().mapToInt(List::size).sum() * POLICY_IDS.size() * VariantLabel.values().length;
    }

    private static void collect(
            List<Faction> names, int size, int from, List<Faction> chosen, List<List<Faction>> sets) {
        if (chosen.size() == size) {
            sets.add(List.copyOf(chosen));
            return;
        }
        for (var index = from; index < names.size(); index++) {
            chosen.add(names.get(index));
            collect(names, size, index + 1, chosen, sets);
            chosen.removeLast();
        }
    }
}
