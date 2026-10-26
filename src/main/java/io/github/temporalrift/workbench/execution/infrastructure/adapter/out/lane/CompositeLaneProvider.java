package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.run.LaneFit;
import io.github.temporalrift.workbench.execution.domain.run.ServedBundle;

/**
 * The lanes of several providers as one: HTTP lanes and simulator slots. A case goes to the provider whose free lane
 * its previous attempt played on, then to the first that has a lane free. Lane ids are unique across providers.
 */
public final class CompositeLaneProvider implements LaneProvider {

    private final List<LaneProvider> providers;

    public CompositeLaneProvider(List<LaneProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    @Override
    public Optional<CaseLane> acquire(LaneFit fit, String preferredLaneId) {
        return providers.stream()
                .sorted(Comparator.comparingInt(provider -> rank(provider, preferredLaneId)))
                .map(provider -> provider.acquire(fit, preferredLaneId))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static int rank(LaneProvider provider, String preferredLaneId) {
        return preferredLaneId != null && provider.freeLanes().containsKey(preferredLaneId) ? 0 : 1;
    }

    @Override
    public Optional<CaseLane> acquireExactly(String laneId, LaneFit fit) {
        return providers.stream()
                .map(provider -> provider.acquireExactly(laneId, fit))
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
    public Map<String, LaneFit> freeLanes() {
        var free = new HashMap<String, LaneFit>();
        providers.forEach(provider -> free.putAll(provider.freeLanes()));
        return Map.copyOf(free);
    }

    @Override
    public void prepare() {
        providers.forEach(LaneProvider::prepare);
    }
}
