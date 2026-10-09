package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.ActiveEvent;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.MySubmission;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.PlayerGameStateResponse;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.PlayerInGame;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.RevealedIntel;
import io.github.temporalrift.workbench.policy.domain.observation.CardGrade;
import io.github.temporalrift.workbench.policy.domain.observation.CardType;
import io.github.temporalrift.workbench.policy.domain.observation.DealtCard;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.DeclarationMode;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.EventView;
import io.github.temporalrift.workbench.policy.domain.observation.Faction;
import io.github.temporalrift.workbench.policy.domain.observation.HandCard;
import io.github.temporalrift.workbench.policy.domain.observation.OutcomeView;
import io.github.temporalrift.workbench.policy.domain.observation.PlayableCard;
import io.github.temporalrift.workbench.policy.domain.observation.PlayableSpecial;
import io.github.temporalrift.workbench.policy.domain.observation.SpecialAction;

/**
 * Builds a seat's entitled observation from that participant's own projection response only. The
 * response is already participant-scoped by the service; nothing from observers, other seats' responses
 * or execution control is consulted.
 */
final class ObservationMapper {

    private static final int HAND_KEEP_COUNT = 5;
    private static final String HAND_SELECTION = "HAND_SELECTION";
    private static final String DECLARATION = "DECLARATION";
    private static final String ACTION = "ACTION";
    private static final String PARADOX_RESOLUTION = "PARADOX_RESOLUTION";

    private ObservationMapper() {}

    /** A decision the seat currently owes, with the coordinates its submission is addressed to. */
    record Owed(EntitledObservation observation, int era, int round) {}

    /**
     * The decision the seat owes in this state, if any. At most one window is open for a seat at a time:
     * hand selection, then the declaration window, then the action rounds and paradox resolution.
     */
    static Optional<Owed> owed(int seatIndex, UUID playerId, PlayerGameStateResponse state) {
        var era = state.getEraNumber();
        var submissions = state.getMySubmissions();
        if (state.getPendingHandSelection() != null && !submitted(submissions, HAND_SELECTION, era, null)) {
            return Optional.of(handSelection(seatIndex, playerId, state));
        }
        var context = state.getPhaseContext();
        if (context == null) {
            return Optional.empty();
        }
        if (Boolean.TRUE.equals(context.getDeclarationOpen())
                && !state.getMyEligibleDeclarationModes().isEmpty()
                && !submitted(submissions, DECLARATION, era, null)) {
            return Optional.of(declaration(seatIndex, playerId, state));
        }
        var round = state.getRoundNumber();
        var progress = context.getActionRoundProgress();
        if (round != null
                && progress != null
                && progress.getPendingPlayerIds().contains(playerId)
                && !submitted(submissions, ACTION, era, round)) {
            return Optional.of(actionRound(seatIndex, playerId, state, round));
        }
        if (Boolean.TRUE.equals(context.getParadoxOpen()) && !submitted(submissions, PARADOX_RESOLUTION, era, null)) {
            return Optional.of(paradox(seatIndex, playerId, state));
        }
        return Optional.empty();
    }

    /** The terminal-readiness observation of an ended game. */
    static EntitledObservation terminal(int seatIndex, UUID playerId, PlayerGameStateResponse state) {
        return observation(
                seatIndex,
                playerId,
                state,
                new DecisionWindow.TerminalReadiness(state.getEraNumber()),
                state.getActiveEvents());
    }

    private static Owed handSelection(int seatIndex, UUID playerId, PlayerGameStateResponse state) {
        var deal = state.getPendingHandSelection().getCards().stream()
                .map(card -> dealt(
                        card.getCardInstanceId(),
                        card.getCardType().name(),
                        card.getGrade().name()))
                .toList();
        var window = new DecisionWindow.HandSelection(state.getEraNumber(), deal, HAND_KEEP_COUNT);
        return new Owed(
                observation(seatIndex, playerId, state, window, state.getActiveEvents()), state.getEraNumber(), 0);
    }

    private static Owed declaration(int seatIndex, UUID playerId, PlayerGameStateResponse state) {
        var modes = state.getMyEligibleDeclarationModes().stream()
                .map(mode -> DeclarationMode.valueOf(mode.name()))
                .sorted()
                .toList();
        var window = new DecisionWindow.Declaration(state.getEraNumber(), modes);
        return new Owed(
                observation(seatIndex, playerId, state, window, state.getActiveEvents()), state.getEraNumber(), 0);
    }

    private static Owed actionRound(int seatIndex, UUID playerId, PlayerGameStateResponse state, int round) {
        var hand = new ArrayList<HandCard>();
        var cards = new ArrayList<PlayableCard>();
        for (var card : state.getMyHand()) {
            var dealt = dealt(
                    card.getCardInstanceId(),
                    card.getCardType().name(),
                    card.getGrade().name());
            var playable = Boolean.TRUE.equals(card.getIsPlayableThisRound());
            hand.add(new HandCard(dealt, playable));
            if (playable) {
                cards.add(new PlayableCard(
                        dealt, CardRules.shape(dealt.type()), CardRules.targetCount(dealt.type(), dealt.grade())));
            }
        }
        var window = new DecisionWindow.ActionRound(state.getEraNumber(), round, hand, cards, specials(state, round));
        return new Owed(
                observation(seatIndex, playerId, state, window, state.getActiveEvents()), state.getEraNumber(), round);
    }

    private static List<PlayableSpecial> specials(PlayerGameStateResponse state, int round) {
        var jammedUntil = state.getMyJammedUntilRound();
        if (jammedUntil != null && round <= jammedUntil) {
            return List.of();
        }
        var exhausted = EnumSet.noneOf(SpecialAction.class);
        for (var budget : state.getMySpecialBudgets()) {
            if (budget.getRemainingUsesThisEra() == 0 || budget.getRemainingUsesThisGame() == 0) {
                exhausted.add(SpecialAction.valueOf(budget.getSpecialAction().name()));
            }
        }
        var specials = new ArrayList<PlayableSpecial>();
        for (var special : state.getMySpecialActions()) {
            var action = SpecialAction.valueOf(special.name());
            if (!CardRules.isDeclarationOnly(action) && !exhausted.contains(action)) {
                specials.add(new PlayableSpecial(action, CardRules.shape(action)));
            }
        }
        return specials;
    }

    private static Owed paradox(int seatIndex, UUID playerId, PlayerGameStateResponse state) {
        var offer = state.getMyEligibleResolutionCards().stream()
                .map(card -> dealt(
                        card.getCardInstanceId(),
                        card.getCardType().name(),
                        card.getGrade().name()))
                .toList();
        var affected = state.getPhaseContext().getAffectedEventIds();
        var events = state.getActiveEvents().stream()
                .filter(event -> affected.isEmpty() || affected.contains(event.getEventId()))
                .toList();
        var window = new DecisionWindow.ParadoxResolution(state.getEraNumber(), offer);
        return new Owed(observation(seatIndex, playerId, state, window, events), state.getEraNumber(), 0);
    }

    private static EntitledObservation observation(
            int seatIndex,
            UUID playerId,
            PlayerGameStateResponse state,
            DecisionWindow window,
            List<ActiveEvent> activeEvents) {
        if (state.getMyFaction() == null) {
            throw new AttemptFailedException(
                    FailureCode.CONFIGURATION_DRIFT, "Seat " + seatIndex + " has no faction in the game state");
        }
        var scanned = scannedWeights(state.getMyRevealedIntel());
        var events = activeEvents.stream()
                .map(event -> new EventView(
                        event.getEventId(),
                        event.getOutcomes().stream()
                                .map(outcome -> new OutcomeView(
                                        outcome.getOutcomeId(),
                                        outcome.getInitialProbability(),
                                        scanned.get(outcome.getOutcomeId())))
                                .toList()))
                .toList();
        var others = state.getPlayers().stream()
                .map(PlayerInGame::getPlayerId)
                .filter(id -> !id.equals(playerId))
                .toList();
        return new EntitledObservation(
                seatIndex, Faction.valueOf(state.getMyFaction().name()), events, others, window);
    }

    /** The exact weights this participant bought itself; the most recent observation of an outcome wins. */
    private static Map<UUID, Integer> scannedWeights(List<RevealedIntel> intel) {
        var weights = new HashMap<UUID, Integer>();
        var observedIn = new HashMap<UUID, Integer>();
        for (var entry : intel) {
            if (!"PROBABILITY".equals(entry.getKind().name())) {
                continue;
            }
            for (var outcome : entry.getOutcomes()) {
                var seen = observedIn.getOrDefault(outcome.getOutcomeId(), -1);
                if (entry.getObservedInRound() >= seen) {
                    observedIn.put(outcome.getOutcomeId(), entry.getObservedInRound());
                    weights.put(outcome.getOutcomeId(), outcome.getProbability());
                }
            }
        }
        return weights;
    }

    private static DealtCard dealt(UUID cardInstanceId, String type, String grade) {
        var cardType = CardType.valueOf(type);
        return new DealtCard(cardInstanceId, cardType, CardGrade.valueOf(grade), CardRules.category(cardType));
    }

    private static boolean submitted(List<MySubmission> submissions, String window, int era, Integer round) {
        return submissions.stream()
                .anyMatch(submission -> submission.getWindow().name().equals(window)
                        && submission.getEraNumber() == era
                        && (round == null || round.equals(submission.getRoundNumber())));
    }
}
