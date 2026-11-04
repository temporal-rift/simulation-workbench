package io.github.temporalrift.workbench.shared.game;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** The sets of distinct factions a game of a given size can be played by. */
public final class FactionSets {

    private FactionSets() {}

    /**
     * Every set of distinct factions of each of the player counts, smallest first, each ordered by faction name and
     * the sets of one size in the order of their names.
     */
    public static List<List<Faction>> of(List<Integer> playerCounts) {
        var names = Arrays.stream(Faction.values())
                .sorted(Comparator.comparing(Faction::name))
                .toList();
        var sets = new ArrayList<List<Faction>>();
        playerCounts.stream().sorted().forEach(size -> collect(names, size, 0, new ArrayList<>(), sets));
        return List.copyOf(sets);
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
