package io.github.temporalrift.workbench.analysis.domain.fact;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import io.github.temporalrift.workbench.analysis.domain.game.CardKey;
import io.github.temporalrift.workbench.analysis.domain.game.Faction;
import io.github.temporalrift.workbench.analysis.domain.game.SpecialAction;

/**
 * What one seat of a succeeded game did and achieved. A card-round is a card the seat held at the start of an
 * action round; its playability is known only when the seat observed that round.
 */
public record SeatFacts(
        int seat,
        Faction faction,
        boolean won,
        boolean sharedWin,
        int score,
        Map<CardKey, Integer> cardsOffered,
        Map<CardKey, Integer> cardsKept,
        Map<CardKey, Integer> cardsPlayed,
        Map<CardKey, Integer> reactiveCardsOffered,
        Map<CardKey, Integer> reactiveCardsPlayed,
        Map<CardKey, Integer> knownCardRounds,
        Map<CardKey, Integer> playableCardRounds,
        Map<CardKey, Integer> unknownCardRounds,
        Map<SpecialAction, Integer> specialAttempts,
        Map<SpecialAction, Integer> specialSubmissionRejections,
        Map<SpecialAction, Integer> specialAccepts,
        Map<SpecialAction, Integer> specialResolutionRejections,
        int declarationOffers,
        Map<SpecialAction, Integer> declarations) {

    public SeatFacts {
        Objects.requireNonNull(faction, "faction");
        cardsOffered = sorted(cardsOffered);
        cardsKept = sorted(cardsKept);
        cardsPlayed = sorted(cardsPlayed);
        reactiveCardsOffered = sorted(reactiveCardsOffered);
        reactiveCardsPlayed = sorted(reactiveCardsPlayed);
        knownCardRounds = sorted(knownCardRounds);
        playableCardRounds = sorted(playableCardRounds);
        unknownCardRounds = sorted(unknownCardRounds);
        specialAttempts = sorted(specialAttempts);
        specialSubmissionRejections = sorted(specialSubmissionRejections);
        specialAccepts = sorted(specialAccepts);
        specialResolutionRejections = sorted(specialResolutionRejections);
        declarations = sorted(declarations);
    }

    private static <K extends Comparable<K>> Map<K, Integer> sorted(Map<K, Integer> counts) {
        return Collections.unmodifiableMap(new TreeMap<>(counts));
    }
}
