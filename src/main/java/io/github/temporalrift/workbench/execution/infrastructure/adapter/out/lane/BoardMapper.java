package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.ActionSummary;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.ChainState;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.MySubmission;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.OpenParadox;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.PlayerGameStateResponse;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.PublicDeclaration;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.RoundSummary;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.SubmissionTargets;
import io.github.temporalrift.workbench.policy.domain.observation.Band;
import io.github.temporalrift.workbench.policy.domain.observation.Board;
import io.github.temporalrift.workbench.policy.domain.observation.CardCategory;
import io.github.temporalrift.workbench.policy.domain.observation.DeclarationMode;
import io.github.temporalrift.workbench.policy.domain.observation.PublicBand;
import io.github.temporalrift.workbench.shared.game.CardGrade;
import io.github.temporalrift.workbench.shared.game.CardType;
import io.github.temporalrift.workbench.shared.game.SpecialAction;
import io.github.temporalrift.workbench.shared.window.WindowKind;

/**
 * Reads a seat's board facts from its own projection response. Only facts settled before the observed window opens
 * are read: the projection already scopes bands and declarations to the open era, own submissions are filtered to
 * it here, and scores, timers and revisions are never read.
 */
final class BoardMapper {

    private BoardMapper() {}

    /** The current era's public band of each outcome that has one. */
    static Map<UUID, PublicBand> bands(PlayerGameStateResponse state) {
        var bands = new HashMap<UUID, PublicBand>();
        for (var event : state.getPublicBands()) {
            for (var outcome : event.getOutcomes()) {
                bands.put(
                        outcome.getOutcomeId(),
                        new PublicBand(Band.valueOf(outcome.getBand().name()), event.getObservedInRound()));
            }
        }
        return bands;
    }

    static Board board(PlayerGameStateResponse state) {
        return new Board(
                state.getDeclarations().stream()
                        .filter(declaration -> declaration.getEraNumber() == state.getEraNumber())
                        .map(BoardMapper::declaration)
                        .toList(),
                summary(state.getLastRoundSummary()),
                ownSubmissions(state),
                chain(state.getChain()),
                state.getPhaseContext() == null
                        ? List.of()
                        : state.getPhaseContext().getParadoxes().stream()
                                .map(BoardMapper::paradox)
                                .toList());
    }

    private static Board.PublicDeclaration declaration(PublicDeclaration declaration) {
        return new Board.PublicDeclaration(
                declaration.getPlayerId(),
                DeclarationMode.valueOf(declaration.getMode().name()),
                declaration.getTargetEventId(),
                declaration.getTargetOutcomeId());
    }

    private static Board.RoundSummary summary(RoundSummary summary) {
        if (summary == null) {
            return null;
        }
        return new Board.RoundSummary(
                summary.getRoundNumber(),
                summary.getActionSummaries().stream().map(BoardMapper::action).toList());
    }

    private static Board.ActionSummary action(ActionSummary action) {
        return new Board.ActionSummary(
                action.getPlayerId(),
                action.getActionCategory() == null
                        ? null
                        : CardCategory.valueOf(action.getActionCategory().name()),
                action.getActionFamily() == null
                        ? null
                        : Board.ActionFamily.valueOf(action.getActionFamily().name()),
                Boolean.TRUE.equals(action.getSkipped()));
    }

    private static List<Board.OwnSubmission> ownSubmissions(PlayerGameStateResponse state) {
        return state.getMySubmissions().stream()
                .filter(submission -> submission.getEraNumber() == state.getEraNumber()
                        && submission.getStatus() == MySubmission.StatusEnum.ACCEPTED)
                .map(BoardMapper::ownSubmission)
                .toList();
    }

    private static Board.OwnSubmission ownSubmission(MySubmission submission) {
        var card = submission.getCard();
        var targets = submission.getTargets();
        return new Board.OwnSubmission(
                window(submission),
                submission.getRoundNumber(),
                card == null ? null : CardType.valueOf(card.getCardType().name()),
                card == null ? null : CardGrade.valueOf(card.getGrade().name()),
                submission.getSpecialAction() == null
                        ? null
                        : SpecialAction.valueOf(submission.getSpecialAction().name()),
                targets == null ? null : targets.getTargetEventId(),
                targets == null ? null : targets.getTargetOutcomeId(),
                targets == null ? null : targets.getSourceOutcomeId(),
                targets == null ? List.of() : List.copyOf(targets.getTargetEventIds()),
                targets == null ? List.of() : players(targets));
    }

    private static WindowKind window(MySubmission submission) {
        return switch (submission.getWindow()) {
            case HAND_SELECTION -> WindowKind.HAND_SELECTION;
            case DECLARATION -> WindowKind.DECLARATION;
            case ACTION -> WindowKind.ACTION_ROUND;
            case PARADOX_RESOLUTION -> WindowKind.PARADOX_RESOLUTION;
        };
    }

    private static List<UUID> players(SubmissionTargets targets) {
        var players = new ArrayList<UUID>();
        if (targets.getTargetPlayerId() != null) {
            players.add(targets.getTargetPlayerId());
        }
        targets.getTargetPlayerIds().stream()
                .filter(player -> !players.contains(player))
                .forEach(players::add);
        return players;
    }

    private static Board.ChainView chain(ChainState chain) {
        if (chain == null) {
            return null;
        }
        return new Board.ChainView(Board.ChainStatus.valueOf(chain.getStatus().name()), chain.getLength());
    }

    private static Board.OpenParadox paradox(OpenParadox paradox) {
        return new Board.OpenParadox(
                Board.ParadoxKind.valueOf(paradox.getType().name()),
                paradox.getAffectedEventId(),
                paradox.getAffectedOutcomeIds());
    }
}
