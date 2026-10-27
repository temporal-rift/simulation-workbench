package io.github.temporalrift.workbench.execution.domain.run;

import java.util.Optional;

/**
 * Whether one lane plays the game of another. Two lanes are build-equivalent when they serve the same content, run the
 * same source revision of each of the three services, implement the same contract versions, and give the first seats
 * the same bot player ids. Image digests are not compared: a jar hosted in this process and a container image of one
 * source revision never share a digest. The rules are not compared either, because every case carries its own rules
 * into the context it opens.
 */
public final class BuildEquivalence {

    private BuildEquivalence() {}

    /**
     * The first way {@code candidate} differs from {@code reference} for a case of {@code seats} seats, named as the
     * declaration that differs, or empty when the candidate is equivalent.
     */
    public static Optional<String> firstDifference(LaneProfile reference, LaneProfile candidate, int seats) {
        var wanted = reference.provenance();
        var offered = candidate.provenance();
        if (!reference.bundle().contentDigest().equals(candidate.bundle().contentDigest())) {
            return Optional.of("content digest");
        }
        if (!wanted.gameService().sourceRevision().equals(offered.gameService().sourceRevision())) {
            return Optional.of("game-service source revision");
        }
        if (!wanted.timelineService()
                .sourceRevision()
                .equals(offered.timelineService().sourceRevision())) {
            return Optional.of("timeline-service source revision");
        }
        if (!wanted.readService().sourceRevision().equals(offered.readService().sourceRevision())) {
            return Optional.of("read-service source revision");
        }
        if (!wanted.contracts().equals(offered.contracts())) {
            return Optional.of("contract versions");
        }
        if (reference.botPlayerIds().size() < seats || candidate.botPlayerIds().size() < seats) {
            return Optional.of("bot identities for " + seats + " seats");
        }
        for (var seat = 0; seat < seats; seat++) {
            if (!reference
                    .botPlayerIds()
                    .get(seat)
                    .equals(candidate.botPlayerIds().get(seat))) {
                return Optional.of("bot player id of seat " + seat);
            }
        }
        return Optional.empty();
    }

    public static boolean holds(LaneProfile reference, LaneProfile candidate, int seats) {
        return firstDifference(reference, candidate, seats).isEmpty();
    }
}
