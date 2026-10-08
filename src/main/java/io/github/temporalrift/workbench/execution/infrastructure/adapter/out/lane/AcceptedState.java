package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.MySubmission;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.PlayerGameStateResponse;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.SubmissionTargets;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.Target;
import io.github.temporalrift.workbench.policy.domain.observation.CardCategory;
import io.github.temporalrift.workbench.policy.domain.observation.CardType;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.DeclarationMode;
import io.github.temporalrift.workbench.policy.domain.observation.SpecialAction;

/**
 * Reads what the service has accepted from a seat in the window it owes, using only that participant's
 * own projection response. This is the recovery source for a command whose acknowledgement was lost.
 */
final class AcceptedState {

    private AcceptedState() {}

    /** The seat's accepted submission for the observed window, if the state holds one. */
    static Optional<Candidate> accepted(
            DecisionWindow window, int round, UUID playerId, PlayerGameStateResponse state) {
        return switch (window) {
            case DecisionWindow.HandSelection w -> hand(state, w.era());
            case DecisionWindow.Declaration w -> declaration(state, w.era(), playerId);
            case DecisionWindow.ActionRound w -> action(state, w.era(), round);
            case DecisionWindow.ParadoxResolution w -> paradox(state, w.era());
            case DecisionWindow.TerminalReadiness _ -> Optional.empty();
        };
    }

    /** Whether the state records an accepted submission in the window, whatever it was. */
    static boolean holds(PlayerGameStateResponse state, WindowKeys.Ref window) {
        return submission(state, window.window(), window.era(), window.round()).isPresent();
    }

    private static Optional<Candidate> hand(PlayerGameStateResponse state, int era) {
        return submission(state, "HAND_SELECTION", era, null).map(_ -> {
            List<UUID> kept = state.getMyHand().stream()
                    .map(card -> card.getCardInstanceId())
                    .sorted(Comparator.comparing(UUID::toString))
                    .toList();
            return new Candidate.KeepHand(kept);
        });
    }

    private static Optional<Candidate> declaration(PlayerGameStateResponse state, int era, UUID playerId) {
        return submission(state, "DECLARATION", era, null)
                .map(_ -> state.getDeclarations().stream()
                        .filter(declaration ->
                                declaration.getPlayerId().equals(playerId) && declaration.getEraNumber() == era)
                        .findFirst()
                        .<Candidate>map(declaration -> new Candidate.Declare(
                                DeclarationMode.valueOf(declaration.getMode().name()),
                                declaration.getTargetEventId(),
                                declaration.getTargetOutcomeId()))
                        .orElseGet(Candidate.Decline::new));
    }

    private static Optional<Candidate> action(PlayerGameStateResponse state, int era, int round) {
        return submission(state, "ACTION", era, round).map(entry -> switch (entry.getChoice()
                .name()) {
            case "CARD" -> {
                var card = entry.getCard();
                var type = CardType.valueOf(card.getCardType().name());
                yield new Candidate.PlayCard(
                        card.getCardInstanceId(),
                        target(
                                CardRules.shape(type),
                                entry.getTargets(),
                                card.getDisguiseCategory() == null
                                        ? null
                                        : CardCategory.valueOf(
                                                card.getDisguiseCategory().name())));
            }
            case "SPECIAL" -> {
                var action = SpecialAction.valueOf(entry.getSpecialAction().name());
                yield new Candidate.PlaySpecial(action, target(CardRules.shape(action), entry.getTargets(), null));
            }
            default -> new Candidate.Pass();
        });
    }

    private static Optional<Candidate> paradox(PlayerGameStateResponse state, int era) {
        return submission(state, "PARADOX_RESOLUTION", era, null).map(entry -> {
            if (!"CARD".equals(entry.getChoice().name())) {
                return new Candidate.PassParadox();
            }
            var targets = entry.getTargets();
            return new Candidate.PlayParadoxCard(
                    entry.getCard().getCardInstanceId(),
                    new Target.EventOutcome(targets.getTargetEventId(), targets.getTargetOutcomeId()));
        });
    }

    private static Target target(
            io.github.temporalrift.workbench.policy.domain.observation.TargetShape shape,
            SubmissionTargets targets,
            CardCategory disguise) {
        return switch (shape) {
            case DISGUISE -> new Target.Disguise(disguise);
            case EVENT_OUTCOME -> new Target.EventOutcome(targets.getTargetEventId(), targets.getTargetOutcomeId());
            case OUTCOME_PAIR ->
                new Target.OutcomePair(
                        targets.getTargetEventId(), targets.getSourceOutcomeId(), targets.getTargetOutcomeId());
            case EVENT_LIST ->
                new Target.Events(sorted(targets.getTargetEventIds().stream().toList()));
            case PLAYER -> new Target.Player(targets.getTargetPlayerId());
            case PLAYER_LIST ->
                new Target.Players(sorted(targets.getTargetPlayerIds().stream().toList()));
        };
    }

    private static List<UUID> sorted(List<UUID> ids) {
        return ids.stream().sorted(Comparator.comparing(UUID::toString)).toList();
    }

    private static Optional<MySubmission> submission(
            PlayerGameStateResponse state, String window, int era, Integer round) {
        return state.getMySubmissions().stream()
                .filter(entry -> entry.getWindow().name().equals(window)
                        && entry.getEraNumber() == era
                        && (round == null || round.equals(entry.getRoundNumber())))
                .findFirst();
    }
}
