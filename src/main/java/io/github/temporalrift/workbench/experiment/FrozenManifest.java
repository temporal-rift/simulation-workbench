package io.github.temporalrift.workbench.experiment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * The gameplay-affecting definition a frozen manifest fixes: everything other modules compare or run, without the
 * designer-facing metadata. Pinned services, contracts and policies keep the shape they were frozen with.
 */
public record FrozenManifest(
        Map<String, Object> services,
        Map<String, Object> contracts,
        String seatRotationMode,
        String timingMode,
        int maxRejectedCandidatesPerWindow,
        List<String> seeds,
        List<Integer> playerCounts,
        List<Map<String, Object>> policies,
        List<List<Faction>> factionSets,
        List<ExperimentVariant> variants) {

    public FrozenManifest {
        services = readOnly(services);
        contracts = readOnly(contracts);
        seeds = List.copyOf(seeds);
        playerCounts = List.copyOf(playerCounts);
        policies = policies.stream().map(FrozenManifest::readOnly).toList();
        factionSets = factionSets.stream().map(List::copyOf).toList();
        variants = List.copyOf(variants);
    }

    private static Map<String, Object> readOnly(Map<String, Object> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
