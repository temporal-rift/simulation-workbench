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

    /** The bundle one lane serves. */
    public static ServedBundle ofLane(BundleDigests digests, BundleProvenance provenance) {
        return new ServedBundle(digests, provenance, 1);
    }

    /**
     * One bundle per distinct digests among {@code lanes}, counting the lanes serving it, ordered by rules digest and
     * then content digest. Lanes of one bundle declare one provenance, so the first one's stands for all.
     */
    public static List<ServedBundle> counted(Collection<ServedBundle> lanes) {
        var byDigests = new LinkedHashMap<BundleDigests, ServedBundle>();
        lanes.forEach(lane -> byDigests.merge(
                lane.digests(),
                lane,
                (counted, more) -> new ServedBundle(
                        counted.digests(), counted.provenance(), counted.laneCount() + more.laneCount())));
        return byDigests.values().stream()
                .sorted(Comparator.comparing(ServedBundle::digests, DIGEST_ORDER))
                .toList();
    }
}
