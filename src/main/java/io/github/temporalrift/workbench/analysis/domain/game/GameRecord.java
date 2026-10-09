package io.github.temporalrift.workbench.analysis.domain.game;

import java.util.List;
import java.util.UUID;

/**
 * What analysis reads of one succeeded game: the authoritative events attributed to seats, what each seat
 * observed of its hand in the action rounds it played, and the special actions it submitted.
 */
public record GameRecord(
        List<GameEvent> events, List<RoundObservation> observations, List<SpecialSubmission> submissions) {

    public GameRecord {
        events = List.copyOf(events);
        observations = List.copyOf(observations);
        submissions = List.copyOf(submissions);
    }

    /** The hand a seat observed at the start of an action round it played. */
    public record RoundObservation(int seat, int era, int round, List<HeldCard> hand) {

        public RoundObservation {
            hand = List.copyOf(hand);
        }
    }

    /** A held card and whether the seat's view marked it playable in that round. */
    public record HeldCard(UUID cardInstanceId, CardKey key, boolean playable) {}

    /** A special action a seat submitted, and whether the service refused it. */
    public record SpecialSubmission(int seat, SpecialAction action, boolean rejected) {}
}
