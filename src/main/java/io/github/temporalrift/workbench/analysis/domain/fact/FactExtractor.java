package io.github.temporalrift.workbench.analysis.domain.fact;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.game.CardKey;
import io.github.temporalrift.workbench.analysis.domain.game.CaseOutcome;
import io.github.temporalrift.workbench.analysis.domain.game.Faction;
import io.github.temporalrift.workbench.analysis.domain.game.GameEvent;
import io.github.temporalrift.workbench.analysis.domain.game.GameRecord;
import io.github.temporalrift.workbench.analysis.domain.game.ParadoxType;
import io.github.temporalrift.workbench.analysis.domain.game.SpecialAction;

/** Reduces a succeeded game to the facts of analysis version 1. */
public final class FactExtractor {

    private FactExtractor() {}

    public static CaseFacts extract(
            UUID caseId, List<Faction> seatFactions, CaseOutcome outcome, GameRecord gameRecord) {
        var seats = new ArrayList<SeatFacts>();
        for (var seat = 0; seat < seatFactions.size(); seat++) {
            seats.add(seat(seat, seatFactions.get(seat), outcome, gameRecord));
        }
        return new CaseFacts(caseId, game(outcome, gameRecord.events()), seats);
    }

    private static GameFacts game(CaseOutcome outcome, List<GameEvent> events) {
        var detected = new LinkedHashMap<UUID, ParadoxType>();
        var resolved = new HashSet<UUID>();
        var cascaded = new HashSet<UUID>();
        var cascadedEvents = new HashSet<UUID>();
        for (var event : events) {
            switch (event) {
                case GameEvent.ParadoxesDetected found ->
                    found.findings().forEach(finding -> detected.putIfAbsent(finding.paradoxId(), finding.type()));
                case GameEvent.ParadoxResolved(var paradoxId) -> resolved.add(paradoxId);
                case GameEvent.ParadoxCascaded(var paradoxIds, var affectedEventId) -> {
                    cascaded.addAll(paradoxIds);
                    cascadedEvents.add(affectedEventId);
                }
                default -> {
                    // not a game-level fact
                }
            }
        }
        var findings = new EnumMap<ParadoxType, Integer>(ParadoxType.class);
        detected.values().forEach(type -> findings.merge(type, 1, Integer::sum));
        resolved.retainAll(detected.keySet());
        cascaded.retainAll(detected.keySet());
        return new GameFacts(
                outcome.endReason(),
                outcome.winnerSeats().size(),
                outcome.eras(),
                outcome.rounds(),
                outcome.decisions(),
                findings,
                resolved.size(),
                cascaded.size(),
                cascadedEvents.size());
    }

    private static SeatFacts seat(int seat, Faction faction, CaseOutcome outcome, GameRecord gameRecord) {
        var offered = new TreeMap<CardKey, Integer>();
        var kept = new TreeMap<CardKey, Integer>();
        var played = new TreeMap<CardKey, Integer>();
        var reactiveOffered = new TreeMap<CardKey, Integer>();
        var reactivePlayed = new TreeMap<CardKey, Integer>();
        var accepts = new EnumMap<SpecialAction, Integer>(SpecialAction.class);
        var resolutionRejections = new EnumMap<SpecialAction, Integer>(SpecialAction.class);
        var declarations = new EnumMap<SpecialAction, Integer>(SpecialAction.class);
        var declarationOffers = 0;
        for (var event : gameRecord.events()) {
            switch (event) {
                case GameEvent.HandDealt dealt
                when dealt.seat() == seat -> dealt.cards().forEach(key -> add(offered, key));
                case GameEvent.HandKept hand
                when hand.seat() == seat -> hand.cards().forEach(card -> add(kept, card.key()));
                case GameEvent.CardPlayed play when play.seat() == seat -> add(played, play.key());
                case GameEvent.ReactiveCardsOffered offer
                when offer.seat() == seat -> offer.cards().forEach(key -> add(reactiveOffered, key));
                case GameEvent.ReactiveCardPlayed play when play.seat() == seat -> add(reactivePlayed, play.key());
                case GameEvent.SpecialPlayed special when special.seat() == seat -> add(accepts, special.action());
                case GameEvent.SpecialRejected special
                when special.seat() == seat -> add(resolutionRejections, special.action());
                case GameEvent.DeclarationOffered offer when offer.seat() == seat -> declarationOffers++;
                case GameEvent.DeclarationRecorded declared
                when declared.seat() == seat -> add(declarations, declared.mode());
                default -> {
                    // another seat's or a game-level fact
                }
            }
        }
        var attempts = new EnumMap<SpecialAction, Integer>(SpecialAction.class);
        var submissionRejections = new EnumMap<SpecialAction, Integer>(SpecialAction.class);
        for (var submission : gameRecord.submissions()) {
            if (submission.seat() == seat) {
                add(attempts, submission.action());
                if (submission.rejected()) {
                    add(submissionRejections, submission.action());
                }
            }
        }
        var cardRounds = CardRounds.of(seat, gameRecord);
        var won = outcome.winnerSeats().contains(seat);
        return new SeatFacts(
                seat,
                faction,
                won,
                won && outcome.winnerSeats().size() > 1,
                outcome.scores().get(seat),
                offered,
                kept,
                played,
                reactiveOffered,
                reactivePlayed,
                cardRounds.known(),
                cardRounds.playable(),
                cardRounds.unknown(),
                attempts,
                submissionRejections,
                accepts,
                resolutionRejections,
                declarationOffers,
                declarations);
    }

    private static <K> void add(Map<K, Integer> counts, K key) {
        counts.merge(key, 1, Integer::sum);
    }

    /**
     * The seat's card-rounds. An observed round counts its observed hand; an unobserved round's held cards are
     * the era's kept hand minus the seat's earlier plays, because hands never carry over between eras and a card
     * leaves the hand only when it is played.
     */
    private record CardRounds(
            Map<CardKey, Integer> known, Map<CardKey, Integer> playable, Map<CardKey, Integer> unknown) {

        static CardRounds of(int seat, GameRecord gameRecord) {
            var hand = SeatHand.of(seat, gameRecord.events());
            var observed = observed(seat, gameRecord.observations());
            var known = new TreeMap<CardKey, Integer>();
            var playable = new TreeMap<CardKey, Integer>();
            var unknown = new TreeMap<CardKey, Integer>();
            for (var round : hand.rounds()) {
                var observation = observed.get(round);
                if (observation == null) {
                    held(round, hand.keptByEra(), hand.playsByEra()).forEach(card -> add(unknown, card.key()));
                } else {
                    observation.hand().forEach(card -> {
                        add(known, card.key());
                        if (card.playable()) {
                            add(playable, card.key());
                        }
                    });
                }
            }
            return new CardRounds(known, playable, unknown);
        }

        /** The first observation of each action round the seat played. */
        private static Map<Round, GameRecord.RoundObservation> observed(
                int seat, List<GameRecord.RoundObservation> observations) {
            var observed = new HashMap<Round, GameRecord.RoundObservation>();
            observations.stream()
                    .filter(observation -> observation.seat() == seat)
                    .forEach(observation ->
                            observed.putIfAbsent(new Round(observation.era(), observation.round()), observation));
            return observed;
        }

        private static List<GameEvent.Card> held(
                Round round,
                Map<Integer, List<GameEvent.Card>> keptByEra,
                Map<Integer, List<GameEvent.CardPlayed>> playsByEra) {
            Set<UUID> spent = new HashSet<>();
            playsByEra.getOrDefault(round.era(), List.of()).stream()
                    .filter(play -> play.round() < round.round())
                    .forEach(play -> spent.add(play.cardInstanceId()));
            return keptByEra.getOrDefault(round.era(), List.of()).stream()
                    .filter(card -> !spent.contains(card.cardInstanceId()))
                    .toList();
        }
    }

    /** The action rounds of a game and the seat's kept hand and plays per era. */
    private record SeatHand(
            TreeSet<Round> rounds,
            Map<Integer, List<GameEvent.Card>> keptByEra,
            Map<Integer, List<GameEvent.CardPlayed>> playsByEra) {

        static SeatHand of(int seat, List<GameEvent> events) {
            var hand = new SeatHand(new TreeSet<>(), new HashMap<>(), new HashMap<>());
            for (var event : events) {
                switch (event) {
                    case GameEvent.ActionRoundStarted(var era, var round) ->
                        hand.rounds().add(new Round(era, round));
                    case GameEvent.HandKept(var owner, var era, var cards)
                    when owner == seat -> hand.keptByEra().put(era, cards);
                    case GameEvent.CardPlayed play
                    when play.seat() == seat ->
                        hand.playsByEra()
                                .computeIfAbsent(play.era(), era -> new ArrayList<>())
                                .add(play);
                    default -> {
                        // not part of the seat's hand
                    }
                }
            }
            return hand;
        }
    }

    private record Round(int era, int round) implements Comparable<Round> {

        @Override
        public int compareTo(Round other) {
            return era != other.era ? Integer.compare(era, other.era) : Integer.compare(round, other.round);
        }
    }
}
