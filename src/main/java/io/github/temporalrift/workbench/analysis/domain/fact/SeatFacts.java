package io.github.temporalrift.workbench.analysis.domain.fact;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import io.github.temporalrift.workbench.analysis.domain.game.CardKey;
import io.github.temporalrift.workbench.analysis.domain.game.WinPath;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.SpecialAction;

/**
 * What one seat of a succeeded game did and achieved. A card-round is a card the seat held at the start of an
 * action round; its playability is known only when the seat observed that round. The bet counts are seat-eras:
 * {@code betsPlaced} where the faction's signature bet was placed, {@code betsScored} of those that recorded a win or
 * a miss reason, {@code betsWon} of those that recorded a win; facts stored without them read them as zero.
 * {@code reasonOccurrences} counts every score reason the seat recorded in the eras it played, a joined record split
 * and a repeated reason counted each time, and {@code betsVoided} the played eras whose placed bet recorded no bet
 * reason.
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
        Map<SpecialAction, Integer> declarations,
        Map<String, Integer> scoreByReason,
        WinPath winPath,
        int winEra,
        int betWins,
        int betMisses,
        int betsPlaced,
        int betsScored,
        int betsWon,
        Set<WinPath> winConditions,
        Map<String, Integer> reasonOccurrences,
        int betsVoided) {

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
        // Facts stored before the diagnostics existed read them as empty.
        scoreByReason = scoreByReason == null ? Map.of() : sorted(scoreByReason);
        // Facts stored before the win conditions existed read them as none.
        winConditions = winConditions == null || winConditions.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(winConditions));
        // Facts stored before the reason occurrences existed read them as empty.
        reasonOccurrences = reasonOccurrences == null ? Map.of() : sorted(reasonOccurrences);
    }

    /** Seat facts without the reason occurrences and voided bets of analysis version 6. */
    public SeatFacts(
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
            Map<SpecialAction, Integer> declarations,
            Map<String, Integer> scoreByReason,
            WinPath winPath,
            int winEra,
            int betWins,
            int betMisses,
            int betsPlaced,
            int betsScored,
            int betsWon,
            Set<WinPath> winConditions) {
        this(
                seat,
                faction,
                won,
                sharedWin,
                score,
                cardsOffered,
                cardsKept,
                cardsPlayed,
                reactiveCardsOffered,
                reactiveCardsPlayed,
                knownCardRounds,
                playableCardRounds,
                unknownCardRounds,
                specialAttempts,
                specialSubmissionRejections,
                specialAccepts,
                specialResolutionRejections,
                declarationOffers,
                declarations,
                scoreByReason,
                winPath,
                winEra,
                betWins,
                betMisses,
                betsPlaced,
                betsScored,
                betsWon,
                winConditions,
                Map.of(),
                0);
    }

    /** Seat facts without the balance diagnostics. */
    public SeatFacts(
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
        this(
                seat,
                faction,
                won,
                sharedWin,
                score,
                cardsOffered,
                cardsKept,
                cardsPlayed,
                reactiveCardsOffered,
                reactiveCardsPlayed,
                knownCardRounds,
                playableCardRounds,
                unknownCardRounds,
                specialAttempts,
                specialSubmissionRejections,
                specialAccepts,
                specialResolutionRejections,
                declarationOffers,
                declarations,
                Map.of(),
                null,
                0,
                0,
                0,
                0,
                0,
                0,
                Set.of(),
                Map.of(),
                0);
    }

    /** Signed points a seat recorded for a score reason in the game. */
    public int points(String reason) {
        return scoreByReason.getOrDefault(reason, 0);
    }

    /** Signature bets that resolved: kept plus lost. */
    public int resolvedBets() {
        return betWins + betMisses;
    }

    private static <K extends Comparable<K>> Map<K, Integer> sorted(Map<K, Integer> counts) {
        return Collections.unmodifiableMap(new TreeMap<>(counts));
    }
}
