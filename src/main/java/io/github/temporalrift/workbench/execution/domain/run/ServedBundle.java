package io.github.temporalrift.workbench.execution.domain.run;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** A bundle some configured lane serves, with its provenance and the number of lanes serving it, busy or free. */
public record ServedBundle(BundleDigests digests, BundleProvenance provenance, int laneCount) {

    private static final Comparator<BundleDigests> DIGEST_ORDER =
            Comparator.comparing(BundleDigests::rulesDigest).thenComparing(BundleDigests::contentDigest);

    public ServedBundle {
        Objects.requireNonNull(digests, "digests");
        Objects.requireNonNull(provenance, "provenance");
        if (laneCount < 1) {
            throw new IllegalArgumentException("laneCount must be at least 1: " + laneCount);
        }
    }

    /** What a case needs of a lane for this bundle's lanes to take it. */
    public LaneFit fit() {
        return LaneFit.of(digests.contentDigest(), provenance);
    }

    /** The bundle one lane serves. */
    public static ServedBundle ofLane(BundleDigests digests, BundleProvenance provenance) {
        return new ServedBundle(digests, provenance, 1);
    }

    /**
     * One bundle per distinct digests and builds among {@code lanes}, counting the lanes serving it, ordered by rules
     * digest, content digest and then fit. Lanes of one bundle declare one provenance, so the first one's stands for
     * all; lanes that serve the same digests from other builds, such as the simulator and a real lane, are other
     * bundles.
     */
    public static List<ServedBundle> counted(Collection<ServedBundle> lanes) {
        var byBuild = new LinkedHashMap<Served, ServedBundle>();
        lanes.forEach(lane -> byBuild.merge(
                new Served(lane.digests(), lane.fit()),
                lane,
                (counted, more) -> new ServedBundle(
                        counted.digests(), counted.provenance(), counted.laneCount() + more.laneCount())));
        return byBuild.values().stream()
                .sorted(Comparator.comparing(ServedBundle::digests, DIGEST_ORDER)
                        .thenComparing(bundle -> bundle.fit().digest()))
                .toList();
    }

    /** What makes two lanes' bundles one: the digests they serve and the builds that serve them. */
    private record Served(BundleDigests digests, LaneFit fit) {}
}
