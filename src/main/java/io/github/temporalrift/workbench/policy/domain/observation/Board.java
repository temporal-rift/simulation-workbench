package io.github.temporalrift.workbench.policy.domain.observation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import io.github.temporalrift.workbench.shared.game.CardGrade;
import io.github.temporalrift.workbench.shared.game.CardType;
import io.github.temporalrift.workbench.shared.game.SpecialAction;
import io.github.temporalrift.workbench.shared.window.WindowKind;

/**
 * The public and own era state a participant sees beside its events and hand. Every fact is settled before the
 * window that observes it opens. {@code lastRoundSummary} is the latest published one, which at Round 1 of an era
 * is still the previous era's Round 3; {@code chain} is the game's public Weaver chain, null when the game has none.
 */
public record Board(
        List<PublicDeclaration> declarations,
        RoundSummary lastRoundSummary,
        List<OwnSubmission> ownSubmissions,
        ChainView chain,
        List<OpenParadox> openParadoxes) {

    /** No board facts: what an observation recorded before board facts existed reads as. */
    private static final String EVENT_ID = "eventId";

    public static final Board EMPTY = new Board(List.of(), null, List.of(), null, List.of());

    public Board {
        declarations = declarations == null ? List.of() : List.copyOf(declarations);
        ownSubmissions = ownSubmissions == null ? List.of() : List.copyOf(ownSubmissions);
        openParadoxes = openParadoxes == null ? List.of() : List.copyOf(openParadoxes);
    }

    /** An Activist declaration of the current era. */
    public record PublicDeclaration(UUID playerId, DeclarationMode mode, UUID eventId, UUID outcomeId) {

        public PublicDeclaration {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(eventId, EVENT_ID);
            Objects.requireNonNull(outcomeId, "outcomeId");
        }
    }

    /** What each participant was publicly seen to do in one action round. */
    public record RoundSummary(int round, List<ActionSummary> actions) {

        public RoundSummary {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    /** One participant's public action: category and family when it acted, or a skip. */
    public record ActionSummary(UUID playerId, CardCategory category, ActionFamily family, boolean skipped) {

        public ActionSummary {
            Objects.requireNonNull(playerId, "playerId");
        }

        /** Whether the public summary shows a Probability Shifter card, the only eligibility signal a seat sees. */
        public boolean showsShifterCard() {
            return !skipped && family == ActionFamily.CARD && category == CardCategory.PROBABILITY_SHIFTER;
        }
    }

    /** Whether a public action was a card or a faction special. */
    public enum ActionFamily {
        CARD,
        SPECIAL
    }

    /**
     * One of the seat's own accepted submissions of the current era: a card (type and grade), a special, or a pass,
     * with its targets. {@code round} is set for action rounds only.
     */
    public record OwnSubmission(
            WindowKind window,
            Integer round,
            CardType card,
            CardGrade grade,
            SpecialAction special,
            UUID eventId,
            UUID outcomeId,
            UUID sourceOutcomeId,
            List<UUID> eventIds,
            List<UUID> playerIds) {

        public OwnSubmission {
            Objects.requireNonNull(window, "window");
            eventIds = eventIds == null ? List.of() : List.copyOf(eventIds);
            playerIds = playerIds == null ? List.of() : List.copyOf(playerIds);
        }
    }

    /**
     * The game's Weaver chain: public, since Weaver links are public facts. {@code pendingLink} is the outcome the
     * Weaver's latest Thread names and the chain has not yet confirmed, null while there is none.
     */
    public record ChainView(ChainStatus status, int confirmedLinks, PendingLink pendingLink) {

        public ChainView {
            Objects.requireNonNull(status, "status");
            if (confirmedLinks < 0) {
                throw new IllegalArgumentException("confirmedLinks must not be negative");
            }
        }

        /** A chain with no visible pending link. */
        public ChainView(ChainStatus status, int confirmedLinks) {
            this(status, confirmedLinks, null);
        }
    }

    /** The event and outcome a Thread named. */
    public record PendingLink(UUID eventId, UUID outcomeId) {

        public PendingLink {
            Objects.requireNonNull(eventId, EVENT_ID);
            Objects.requireNonNull(outcomeId, "outcomeId");
        }
    }

    public enum ChainStatus {
        ACTIVE,
        COMPLETED,
        BROKEN
    }

    /** A paradox open for resolution, with the event and outcomes it affects. */
    public record OpenParadox(ParadoxKind kind, UUID eventId, List<UUID> outcomeIds) {

        public OpenParadox {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(eventId, EVENT_ID);
            outcomeIds = outcomeIds == null ? List.of() : List.copyOf(outcomeIds);
        }
    }

    public enum ParadoxKind {
        DEAD_HEAT,
        IMPOSSIBLE_ERASURE,
        CHAIN_CONFLICT,
        FATE_CONTRADICTION
    }
}
