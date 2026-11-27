package io.github.temporalrift.workbench.analysis.domain.game;

/**
 * The policy that played a seat, by id and version, and by artifact digest when it tells apart parameter sets of one
 * bundle; the digest is null for a seat whose bundle the id and version name.
 */
public record SeatPolicy(String id, String version, String digest) {

    public SeatPolicy(String id, String version) {
        this(id, version, null);
    }
}
