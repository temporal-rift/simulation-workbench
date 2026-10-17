package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.ServedBundle;

/**
 * The lanes of several providers as one: HTTP lanes and simulator slots. A case goes to the provider that already holds
 * its game, then to the one whose free lane its previous attempt played on, then to the first that has a lane free.
 * Lane ids are unique across providers.
 */
public final class CompositeLaneProvider implements LaneProvider {

    private final List<LaneProvider> providers;

    public CompositeLaneProvider(List<LaneProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    @Override
    public Optional<CaseLane> acquire(UUID caseId, BundleDigests bundle, String preferredLaneId) {
        return providers.stream()
                .sorted(Comparator.comparingInt(provider -> rank(provider, caseId, preferredLaneId)))
                .map(provider -> provider.acquire(caseId, bundle, preferredLaneId))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static int rank(LaneProvider provider, UUID caseId, String preferredLaneId) {
        if (provider.heldCases().contains(caseId)) {
            return 0;
        }
        return preferredLaneId != null && provider.freeLanes().containsKey(preferredLaneId) ? 1 : 2;
    }

    @Override
    public Optional<CaseLane> acquireExactly(String laneId, BundleDigests bundle) {
        return providers.stream()
                .map(provider -> provider.acquireExactly(laneId, bundle))
                .flatMap(Optional::stream)
                .findFirst();
    }

    @Override
    public List<ServedBundle> servedBundles() {
        return ServedBundle.counted(providers.stream()
                .flatMap(provider -> provider.servedBundles().stream())
                .toList());
    }

    @Override
    public Map<String, BundleDigests> freeLanes() {
        var free = new HashMap<String, BundleDigests>();
        providers.forEach(provider -> free.putAll(provider.freeLanes()));
        return Map.copyOf(free);
    }

    @Override
    public Set<UUID> heldCases() {
        var held = new HashSet<UUID>();
        providers.forEach(provider -> held.addAll(provider.heldCases()));
        return Set.copyOf(held);
    }

    @Override
    public void prepare(Collection<Hold> interrupted) {
        providers.forEach(provider -> provider.prepare(interrupted));
    }

    @Override
    public void releaseHeld(UUID caseId) {
        providers.forEach(provider -> provider.releaseHeld(caseId));
    }
}
