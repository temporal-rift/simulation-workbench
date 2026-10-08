package io.github.temporalrift.workbench.policy.domain.observation;

import java.util.List;

/** A normal decision window, carrying only what its participant may act on. */
public sealed interface DecisionWindow {

    /** Stable identifier of the window, used to separate policy entropy streams. */
    String key();

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
        public String key() {
            return "era" + era + "/hand-selection";
        }
    }

    /** Declare an event outcome in an eligible mode, or decline. Targets come from the observed events. */
    record Declaration(int era, List<DeclarationMode> eligibleModes) implements DecisionWindow {
        public Declaration {
            eligibleModes = List.copyOf(eligibleModes);
        }

        @Override
        public String key() {
            return "era" + era + "/declaration";
        }
    }

    /** Play a card or special, or pass. */
    record ActionRound(int era, int round, List<PlayableCard> cards, List<PlayableSpecial> specials)
            implements DecisionWindow {
        public ActionRound {
            cards = List.copyOf(cards);
            specials = List.copyOf(specials);
        }

        @Override
        public String key() {
            return "era" + era + "/round" + round + "/action";
        }
    }

    /** Play an offered reactive card against an affected event outcome, or pass. */
    record ParadoxResolution(int era, List<DealtCard> offer) implements DecisionWindow {
        public ParadoxResolution {
            offer = List.copyOf(offer);
        }

        @Override
        public String key() {
            return "era" + era + "/paradox-resolution";
        }
    }

    /** Signal readiness to finish. */
    record TerminalReadiness(int era) implements DecisionWindow {
        @Override
        public String key() {
            return "era" + era + "/terminal-readiness";
        }
    }
}
