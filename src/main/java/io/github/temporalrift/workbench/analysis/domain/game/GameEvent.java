package io.github.temporalrift.workbench.analysis.domain.game;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** An authoritative fact a game published, already attributed to the seat it concerns. */
public sealed interface GameEvent {

    /** A card instance of a hand. */
    record Card(UUID cardInstanceId, CardKey key) {

        public Card {
            Objects.requireNonNull(cardInstanceId, "cardInstanceId");
            Objects.requireNonNull(key, "key");
        }
    }

    /** The cards dealt to a seat for hand selection. */
    record HandDealt(int seat, int era, List<CardKey> cards) implements GameEvent {

        public HandDealt {
            cards = List.copyOf(cards);
        }
    }

    /** The cards a seat kept: its whole hand for the era. */
    record HandKept(int seat, int era, List<Card> cards) implements GameEvent {

        public HandKept {
            cards = List.copyOf(cards);
        }
    }

    /** An action round opened. */
    record ActionRoundStarted(int era, int round) implements GameEvent {}

    /** A seat played a card of its hand in an action round. */
    record CardPlayed(int seat, int era, int round, UUID cardInstanceId, CardKey key) implements GameEvent {}

    /** Reactive cards offered to a seat during paradox resolution. */
    record ReactiveCardsOffered(int seat, int era, List<CardKey> cards) implements GameEvent {

        public ReactiveCardsOffered {
            cards = List.copyOf(cards);
        }
    }

    /** A seat played an offered reactive card. */
    record ReactiveCardPlayed(int seat, int era, CardKey key) implements GameEvent {}

    /** A seat's special action was played. */
    record SpecialPlayed(int seat, int era, SpecialAction action) implements GameEvent {}

    /** A seat's accepted special action was rejected when the timeline resolved it. */
    record SpecialRejected(int seat, int era, SpecialAction action) implements GameEvent {}

    /** A seat was offered the declaration window. */
    record DeclarationOffered(int seat, int era) implements GameEvent {}

    /** A seat declared in a mode. */
    record DeclarationRecorded(int seat, int era, SpecialAction mode) implements GameEvent {}

    /** Paradox findings detected at once. */
    record ParadoxesDetected(int era, List<Finding> findings) implements GameEvent {

        public ParadoxesDetected {
            findings = List.copyOf(findings);
        }
    }

    /** One paradox finding. */
    record Finding(UUID paradoxId, ParadoxType type) {}

    /** A paradox finding was resolved. */
    record ParadoxResolved(UUID paradoxId) implements GameEvent {}

    /** Paradox findings cascaded on one event. */
    record ParadoxCascaded(List<UUID> paradoxIds, UUID affectedEventId) implements GameEvent {

        public ParadoxCascaded {
            paradoxIds = List.copyOf(paradoxIds);
        }
    }
}
