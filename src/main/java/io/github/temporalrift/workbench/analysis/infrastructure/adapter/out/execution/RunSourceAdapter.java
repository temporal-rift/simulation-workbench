package io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.execution;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.asyncapi.actionevents.GeneratedChannelContract.ActionRoundStartedPayload;
import io.github.temporalrift.asyncapi.actionevents.GeneratedChannelContract.ActivistDeclarationRecordedPayload;
import io.github.temporalrift.asyncapi.actionevents.GeneratedChannelContract.CardPlayedPayload;
import io.github.temporalrift.asyncapi.actionevents.GeneratedChannelContract.DeclarationOptionsOfferedPayload;
import io.github.temporalrift.asyncapi.actionevents.GeneratedChannelContract.ParadoxResolutionCardPlayedPayload;
import io.github.temporalrift.asyncapi.actionevents.GeneratedChannelContract.ParadoxResolutionCardsOfferedPayload;
import io.github.temporalrift.asyncapi.actionevents.GeneratedChannelContract.SpecialActionPlayedPayload;
import io.github.temporalrift.asyncapi.sessionevents.GeneratedChannelContract.FactionAssignedPayload;
import io.github.temporalrift.asyncapi.sessionevents.GeneratedChannelContract.HandDealtPayload;
import io.github.temporalrift.asyncapi.sessionevents.GeneratedChannelContract.HandSelectedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ParadoxCascadedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ParadoxDetectedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.ParadoxResolvedPayload;
import io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.SpecialRejectedPayload;
import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.game.CardGrade;
import io.github.temporalrift.workbench.analysis.domain.game.CardKey;
import io.github.temporalrift.workbench.analysis.domain.game.CardType;
import io.github.temporalrift.workbench.analysis.domain.game.CaseOutcome;
import io.github.temporalrift.workbench.analysis.domain.game.CaseStatus;
import io.github.temporalrift.workbench.analysis.domain.game.EndReason;
import io.github.temporalrift.workbench.analysis.domain.game.Faction;
import io.github.temporalrift.workbench.analysis.domain.game.GameEvent;
import io.github.temporalrift.workbench.analysis.domain.game.GameRecord;
import io.github.temporalrift.workbench.analysis.domain.game.ParadoxType;
import io.github.temporalrift.workbench.analysis.domain.game.SpecialAction;
import io.github.temporalrift.workbench.analysis.domain.port.out.RunSource;
import io.github.temporalrift.workbench.execution.CaseEvidence;
import io.github.temporalrift.workbench.execution.RunCase;
import io.github.temporalrift.workbench.execution.RunCatalog;
import io.github.temporalrift.workbench.execution.RunSummary;

/**
 * Reads runs through the execution module's public API and turns a counting game's retained evidence into the
 * typed record analysis works on. Events name players; each faction occurs once per game, so a player's assigned
 * faction identifies its seat.
 */
public class RunSourceAdapter implements RunSource {

    private static final String ACTION_ROUND = "ACTION_ROUND";
    private static final String SPECIAL_DECISION = "special:";
    private static final Set<String> DECIDED = Set.of("ACCEPTED", "REJECTED");

    private final RunCatalog catalog;
    private final ObjectMapper objectMapper;

    public RunSourceAdapter(RunCatalog catalog, ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<UUID> experimentOf(UUID runId) {
        return catalog.run(runId).map(RunSummary::experimentId);
    }

    @Override
    public List<AnalyzedCase> cases(UUID runId) {
        return catalog.cases(runId).stream().map(RunSourceAdapter::analyzed).toList();
    }

    @Override
    public Optional<GameRecord> countingGame(UUID runId, AnalyzedCase analyzedCase) {
        return catalog.evidence(runId, analyzedCase.caseId()).map(evidence -> gameRecord(analyzedCase, evidence));
    }

    private static AnalyzedCase analyzed(RunCase runCase) {
        var policy = runCase.seats().getFirst();
        return new AnalyzedCase(
                runCase.caseId(),
                runCase.variantLabel(),
                runCase.seed(),
                runCase.playerCount(),
                policy.policyId(),
                policy.policyVersion(),
                runCase.seats().stream()
                        .map(seat -> Faction.valueOf(seat.faction()))
                        .toList(),
                CaseStatus.valueOf(runCase.state()),
                runCase.result() == null ? null : outcome(runCase));
    }

    private static CaseOutcome outcome(RunCase runCase) {
        var result = runCase.result();
        var scores = new ArrayList<Integer>();
        runCase.seats().forEach(seat -> scores.add(0));
        result.finalScores().forEach(score -> scores.set(score.seatIndex(), score.score()));
        return new CaseOutcome(
                EndReason.valueOf(result.endReason()),
                Set.copyOf(result.winnerSeats()),
                scores,
                result.eras(),
                result.rounds(),
                result.decisions());
    }

    private GameRecord gameRecord(AnalyzedCase analyzedCase, CaseEvidence evidence) {
        var seatByFaction = new EnumMap<Faction, Integer>(Faction.class);
        for (var seat = 0; seat < analyzedCase.seatFactions().size(); seat++) {
            seatByFaction.put(analyzedCase.seatFactions().get(seat), seat);
        }
        var seatByPlayer = new HashMap<UUID, Integer>();
        evidence.events().stream()
                .filter(event -> SessionEvents.FACTION_ASSIGNED_EVENT_TYPE.equals(event.eventType()))
                .map(event -> read(event, FactionAssignedPayload.class))
                .forEach(assigned -> Optional.ofNullable(seatByFaction.get(
                                Faction.valueOf(assigned.faction().name())))
                        .ifPresent(seat -> seatByPlayer.put(assigned.playerId(), seat)));
        var events = evidence.events().stream()
                .flatMap(event -> gameEvents(event, seatByPlayer))
                .toList();
        return new GameRecord(events, observations(evidence.steps()), submissions(evidence.steps()));
    }

    private Stream<GameEvent> gameEvents(CaseEvidence.Event event, Map<UUID, Integer> seats) {
        return switch (event.eventType()) {
            case SessionEvents.HAND_DEALT_EVENT_TYPE -> {
                var dealt = read(event, HandDealtPayload.class);
                yield seated(
                        seats,
                        dealt.playerId(),
                        seat -> new GameEvent.HandDealt(
                                seat,
                                dealt.eraNumber(),
                                dealt.cards().stream()
                                        .map(card -> key(
                                                card.cardType().name(),
                                                card.grade().name()))
                                        .toList()));
            }
            case SessionEvents.HAND_SELECTED_EVENT_TYPE -> {
                var kept = read(event, HandSelectedPayload.class);
                yield seated(
                        seats,
                        kept.playerId(),
                        seat -> new GameEvent.HandKept(
                                seat,
                                kept.eraNumber(),
                                kept.cards().stream()
                                        .map(card -> new GameEvent.Card(
                                                card.cardInstanceId(),
                                                key(
                                                        card.cardType().name(),
                                                        card.grade().name())))
                                        .toList()));
            }
            case ActionEvents.ACTION_ROUND_STARTED_EVENT_TYPE -> {
                var started = read(event, ActionRoundStartedPayload.class);
                yield Stream.of(new GameEvent.ActionRoundStarted(started.eraNumber(), started.roundNumber()));
            }
            case ActionEvents.CARD_PLAYED_EVENT_TYPE -> {
                var played = read(event, CardPlayedPayload.class);
                yield seated(
                        seats,
                        played.playerId(),
                        seat -> new GameEvent.CardPlayed(
                                seat,
                                played.eraNumber(),
                                played.roundNumber(),
                                played.cardInstanceId(),
                                key(played.cardType().name(), played.grade().name())));
            }
            case ActionEvents.PARADOX_RESOLUTION_CARDS_OFFERED_EVENT_TYPE -> {
                var offered = read(event, ParadoxResolutionCardsOfferedPayload.class);
                yield seated(
                        seats,
                        offered.playerId(),
                        seat -> new GameEvent.ReactiveCardsOffered(
                                seat,
                                offered.eraNumber(),
                                offered.cards().stream()
                                        .map(card -> key(
                                                card.cardType().name(),
                                                card.grade().name()))
                                        .toList()));
            }
            case ActionEvents.PARADOX_RESOLUTION_CARD_PLAYED_EVENT_TYPE -> {
                var played = read(event, ParadoxResolutionCardPlayedPayload.class);
                yield seated(
                        seats,
                        played.playerId(),
                        seat -> new GameEvent.ReactiveCardPlayed(
                                seat,
                                played.eraNumber(),
                                key(played.cardType().name(), played.grade().name())));
            }
            case ActionEvents.SPECIAL_ACTION_PLAYED_EVENT_TYPE -> {
                var special = read(event, SpecialActionPlayedPayload.class);
                yield seated(
                        seats,
                        special.playerId(),
                        seat -> new GameEvent.SpecialPlayed(
                                seat,
                                special.eraNumber(),
                                SpecialAction.valueOf(special.specialAction().name())));
            }
            case TimelineEvents.SPECIAL_REJECTED_EVENT_TYPE -> {
                var rejected = read(event, SpecialRejectedPayload.class);
                yield seated(
                        seats,
                        rejected.playerId(),
                        seat -> new GameEvent.SpecialRejected(
                                seat,
                                rejected.eraNumber(),
                                SpecialAction.valueOf(rejected.specialAction().name())));
            }
            case ActionEvents.DECLARATION_OPTIONS_OFFERED_EVENT_TYPE -> {
                var offered = read(event, DeclarationOptionsOfferedPayload.class);
                yield seated(
                        seats, offered.playerId(), seat -> new GameEvent.DeclarationOffered(seat, offered.eraNumber()));
            }
            case ActionEvents.ACTIVIST_DECLARATION_RECORDED_EVENT_TYPE -> {
                var declared = read(event, ActivistDeclarationRecordedPayload.class);
                yield seated(
                        seats,
                        declared.playerId(),
                        seat -> new GameEvent.DeclarationRecorded(
                                seat,
                                declared.eraNumber(),
                                SpecialAction.valueOf(declared.mode().name())));
            }
            case TimelineEvents.PARADOX_DETECTED_EVENT_TYPE -> {
                var detected = read(event, ParadoxDetectedPayload.class);
                yield Stream.of(new GameEvent.ParadoxesDetected(
                        detected.eraNumber(),
                        detected.paradoxes().stream()
                                .map(paradox -> new GameEvent.Finding(
                                        paradox.paradoxId(),
                                        ParadoxType.valueOf(paradox.type().name())))
                                .toList()));
            }
            case TimelineEvents.PARADOX_RESOLVED_EVENT_TYPE ->
                Stream.of(new GameEvent.ParadoxResolved(
                        read(event, ParadoxResolvedPayload.class).paradoxId()));
            case TimelineEvents.PARADOX_CASCADED_EVENT_TYPE -> {
                var cascaded = read(event, ParadoxCascadedPayload.class);
                var ids = new HashSet<UUID>();
                Optional.ofNullable(cascaded.paradoxId()).ifPresent(ids::add);
                Optional.ofNullable(cascaded.paradoxIds()).ifPresent(ids::addAll);
                yield Stream.of(new GameEvent.ParadoxCascaded(List.copyOf(ids), cascaded.affectedEventId()));
            }
            default -> Stream.empty();
        };
    }

    private List<GameRecord.RoundObservation> observations(List<CaseEvidence.Step> steps) {
        var observations = new ArrayList<GameRecord.RoundObservation>();
        var seen = new HashSet<String>();
        for (var step : steps) {
            if (!ACTION_ROUND.equals(step.phase()) || step.era() == null || step.round() == null) {
                continue;
            }
            var window = step.seatIndex() + "/" + step.era() + "/" + step.round();
            var hand = tree(step.observation()).at("/window/hand");
            // An observation retained without its hand leaves the round's playability unknown.
            if (hand.isArray() && seen.add(window)) {
                var held = new ArrayList<GameRecord.HeldCard>();
                hand.forEach(card -> held.add(new GameRecord.HeldCard(
                        UUID.fromString(card.at("/card/cardInstanceId").asString()),
                        key(
                                card.at("/card/type").asString(),
                                card.at("/card/grade").asString()),
                        card.at("/playable").asBoolean())));
                observations.add(new GameRecord.RoundObservation(step.seatIndex(), step.era(), step.round(), held));
            }
        }
        return observations;
    }

    private static List<GameRecord.SpecialSubmission> submissions(List<CaseEvidence.Step> steps) {
        return steps.stream()
                .filter(step -> step.decision().startsWith(SPECIAL_DECISION) && DECIDED.contains(step.outcome()))
                .map(step -> new GameRecord.SpecialSubmission(
                        step.seatIndex(),
                        SpecialAction.valueOf(step.decision().split(":", 3)[1]),
                        "REJECTED".equals(step.outcome())))
                .toList();
    }

    private static Stream<GameEvent> seated(Map<UUID, Integer> seats, UUID playerId, IntFunction<GameEvent> event) {
        var seat = seats.get(playerId);
        return seat == null ? Stream.empty() : Stream.of(event.apply(seat));
    }

    private static CardKey key(String type, String grade) {
        return new CardKey(CardType.valueOf(type), CardGrade.valueOf(grade));
    }

    private <T> T read(CaseEvidence.Event event, Class<T> type) {
        try {
            return objectMapper.readValue(event.payload(), type);
        } catch (JacksonException e) {
            throw new IllegalStateException(
                    "Retained " + event.eventType() + " evidence does not match the pinned event contract", e);
        }
    }

    private JsonNode tree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalStateException("A retained observation is not JSON", e);
        }
    }

    /** Event types of the session events contract, usable as switch labels. */
    private static final class SessionEvents {

        static final String FACTION_ASSIGNED_EVENT_TYPE =
                io.github.temporalrift.asyncapi.sessionevents.GeneratedChannelContract.FACTION_ASSIGNED_EVENT_TYPE;
        static final String HAND_DEALT_EVENT_TYPE =
                io.github.temporalrift.asyncapi.sessionevents.GeneratedChannelContract.HAND_DEALT_EVENT_TYPE;
        static final String HAND_SELECTED_EVENT_TYPE =
                io.github.temporalrift.asyncapi.sessionevents.GeneratedChannelContract.HAND_SELECTED_EVENT_TYPE;

        private SessionEvents() {}
    }

    /** Event types of the action events contract, usable as switch labels. */
    private static final class ActionEvents {

        static final String ACTION_ROUND_STARTED_EVENT_TYPE =
                io.github.temporalrift.asyncapi.actionevents.GeneratedChannelContract.ACTION_ROUND_STARTED_EVENT_TYPE;
        static final String ACTIVIST_DECLARATION_RECORDED_EVENT_TYPE = io.github.temporalrift.asyncapi.actionevents
                .GeneratedChannelContract.ACTIVIST_DECLARATION_RECORDED_EVENT_TYPE;
        static final String CARD_PLAYED_EVENT_TYPE =
                io.github.temporalrift.asyncapi.actionevents.GeneratedChannelContract.CARD_PLAYED_EVENT_TYPE;
        static final String DECLARATION_OPTIONS_OFFERED_EVENT_TYPE = io.github.temporalrift.asyncapi.actionevents
                .GeneratedChannelContract.DECLARATION_OPTIONS_OFFERED_EVENT_TYPE;
        static final String PARADOX_RESOLUTION_CARDS_OFFERED_EVENT_TYPE = io.github.temporalrift.asyncapi.actionevents
                .GeneratedChannelContract.PARADOX_RESOLUTION_CARDS_OFFERED_EVENT_TYPE;
        static final String PARADOX_RESOLUTION_CARD_PLAYED_EVENT_TYPE = io.github.temporalrift.asyncapi.actionevents
                .GeneratedChannelContract.PARADOX_RESOLUTION_CARD_PLAYED_EVENT_TYPE;
        static final String SPECIAL_ACTION_PLAYED_EVENT_TYPE =
                io.github.temporalrift.asyncapi.actionevents.GeneratedChannelContract.SPECIAL_ACTION_PLAYED_EVENT_TYPE;

        private ActionEvents() {}
    }

    /** Event types of the timeline events contract, usable as switch labels. */
    private static final class TimelineEvents {

        static final String PARADOX_CASCADED_EVENT_TYPE =
                io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.PARADOX_CASCADED_EVENT_TYPE;
        static final String PARADOX_DETECTED_EVENT_TYPE =
                io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.PARADOX_DETECTED_EVENT_TYPE;
        static final String PARADOX_RESOLVED_EVENT_TYPE =
                io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.PARADOX_RESOLVED_EVENT_TYPE;
        static final String SPECIAL_REJECTED_EVENT_TYPE =
                io.github.temporalrift.asyncapi.timelineevents.GeneratedChannelContract.SPECIAL_REJECTED_EVENT_TYPE;

        private TimelineEvents() {}
    }
}
