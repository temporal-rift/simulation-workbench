package io.github.temporalrift.workbench.policy.domain.observation;

import java.util.List;
import java.util.stream.Collectors;

import io.github.temporalrift.workbench.shared.window.WindowKind;
import io.github.temporalrift.workbench.shared.window.WindowRef;

/** A normal decision window, carrying only what its participant may act on. */
public sealed interface DecisionWindow {

    /** The window's identity, which also separates policy entropy streams. */
    WindowRef ref();

    /** The era the window belongs to. */
    int era();

    /** Choose {@code keepCount} cards from the dealt hand. */
    record HandSelection(int era, List<DealtCard> deal, int keepCount) implements DecisionWindow {
        public HandSelection {
            deal = List.copyOf(deal);
            if (keepCount < 1) {
                throw new IllegalArgumentException("keepCount must be positive");
            }
        }

        @Override
        public WindowRef ref() {
            return WindowRef.of(WindowKind.HAND_SELECTION, era);
        }
    }

    /** Declare an event outcome in an eligible mode, or decline. Targets come from the observed events. */
    record Declaration(int era, List<DeclarationMode> eligibleModes) implements DecisionWindow {
        public Declaration {
            eligibleModes = List.copyOf(eligibleModes);
        }

        @Override
        public WindowRef ref() {
            return WindowRef.of(WindowKind.DECLARATION, era);
        }
    }

    /**
     * Play a card or special, or pass. {@code hand} is the whole hand the seat holds; {@code cards} are the
     * playable ones it may choose from.
     */
    record ActionRound(
            int era, int round, List<HandCard> hand, List<PlayableCard> cards, List<PlayableSpecial> specials)
            implements DecisionWindow {
        public ActionRound {
            hand = List.copyOf(hand);
            cards = List.copyOf(cards);
            specials = List.copyOf(specials);
            var playable =
                    hand.stream().filter(HandCard::playable).map(HandCard::card).collect(Collectors.toSet());
            if (!cards.stream().map(PlayableCard::card).allMatch(playable::contains)) {
                throw new IllegalArgumentException("every playable card must be a playable card of the hand");
            }
        }

        @Override
        public WindowRef ref() {
            return WindowRef.actionRound(era, round);
        }
    }

    /** Play an offered reactive card against an affected event outcome, or pass. */
    record ParadoxResolution(int era, List<DealtCard> offer) implements DecisionWindow {
        public ParadoxResolution {
            offer = List.copyOf(offer);
        }

        @Override
        public WindowRef ref() {
            return WindowRef.of(WindowKind.PARADOX_RESOLUTION, era);
        }
    }

    /** Signal readiness to finish. */
    record TerminalReadiness(int era) implements DecisionWindow {
        @Override
        public WindowRef ref() {
            return WindowRef.of(WindowKind.TERMINAL_READINESS, era);
        }
    }
}
