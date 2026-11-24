package io.github.temporalrift.workbench.analysis.domain.report;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.fact.CaseFacts;
import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.game.CardKey;
import io.github.temporalrift.workbench.analysis.domain.game.ParadoxType;
import io.github.temporalrift.workbench.analysis.domain.game.WinPath;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.SpecialAction;

/**
 * The dimension values a report lists: every faction of the run's faction sets per player count, and the card,
 * special action, declaration and paradox values that occur anywhere in the run.
 */
public record Vocabulary(
        Map<Integer, Set<Faction>> factionsByPlayerCount,
        SortedSet<CardKey> cards,
        Set<SpecialAction> specials,
        Set<SpecialAction> declarationModes,
        Set<ParadoxType> paradoxTypes,
        Map<Faction, SortedSet<String>> scoreReasons,
        Set<WinPath> winPaths,
        boolean diagnostics,
        boolean betSurvival) {

    /** A vocabulary without the balance diagnostics of analysis version 3. */
    public Vocabulary(
            Map<Integer, Set<Faction>> factionsByPlayerCount,
            SortedSet<CardKey> cards,
            Set<SpecialAction> specials,
            Set<SpecialAction> declarationModes,
            Set<ParadoxType> paradoxTypes) {
        this(factionsByPlayerCount, cards, specials, declarationModes, paradoxTypes, Map.of(), Set.of(), false, false);
    }

    /** The vocabulary of a run analysed without the balance diagnostics. */
    public static Vocabulary of(Collection<AnalyzedCase> cases, Collection<CaseFacts> facts) {
        return of(cases, facts, false);
    }

    /** The vocabulary of a run as the analysis version reports it. */
    public static Vocabulary of(Collection<AnalyzedCase> cases, Collection<CaseFacts> facts, AnalysisVersion analysis) {
        return of(cases, facts, analysis.reportsDiagnostics(), analysis.reportsBetSurvival());
    }

    /** The vocabulary of a run; {@code diagnostics} adds the score reasons and win paths that occur in it. */
    public static Vocabulary of(Collection<AnalyzedCase> cases, Collection<CaseFacts> facts, boolean diagnostics) {
        return of(cases, facts, diagnostics, false);
    }

    private static Vocabulary of(
            Collection<AnalyzedCase> cases, Collection<CaseFacts> facts, boolean diagnostics, boolean betSurvival) {
        var factions = new TreeMap<Integer, Set<Faction>>();
        cases.forEach(analyzedCase -> factions.computeIfAbsent(
                        analyzedCase.playerCount(), ignored -> EnumSet.noneOf(Faction.class))
                .addAll(analyzedCase.seatFactions()));
        var cards = new TreeSet<CardKey>();
        var specials = EnumSet.noneOf(SpecialAction.class);
        var modes = EnumSet.noneOf(SpecialAction.class);
        var paradoxTypes = EnumSet.noneOf(ParadoxType.class);
        var reasons = new TreeMap<Faction, SortedSet<String>>();
        var paths = EnumSet.noneOf(WinPath.class);
        for (var caseFacts : facts) {
            paradoxTypes.addAll(caseFacts.game().findings().keySet());
            for (var seat : caseFacts.seats()) {
                cards.addAll(seat.cardsOffered().keySet());
                cards.addAll(seat.cardsKept().keySet());
                cards.addAll(seat.cardsPlayed().keySet());
                cards.addAll(seat.reactiveCardsOffered().keySet());
                cards.addAll(seat.reactiveCardsPlayed().keySet());
                cards.addAll(seat.knownCardRounds().keySet());
                cards.addAll(seat.unknownCardRounds().keySet());
                specials.addAll(seat.specialAttempts().keySet());
                specials.addAll(seat.specialAccepts().keySet());
                specials.addAll(seat.specialResolutionRejections().keySet());
                modes.addAll(seat.declarations().keySet());
                if (diagnostics) {
                    reasons.computeIfAbsent(seat.faction(), ignored -> new TreeSet<>())
                            .addAll(seat.scoreByReason().keySet());
                    if (seat.winPath() != null) {
                        paths.add(seat.winPath());
                    }
                }
            }
        }
        return new Vocabulary(factions, cards, specials, modes, paradoxTypes, reasons, paths, diagnostics, betSurvival);
    }

    /** Merges the values of two vocabularies, as a comparison lists both sides' values. */
    public Vocabulary and(Vocabulary other) {
        var factions = new TreeMap<Integer, Set<Faction>>();
        factionsByPlayerCount.forEach(
                (count, set) -> factions.computeIfAbsent(count, ignored -> EnumSet.noneOf(Faction.class))
                        .addAll(set));
        other.factionsByPlayerCount.forEach(
                (count, set) -> factions.computeIfAbsent(count, ignored -> EnumSet.noneOf(Faction.class))
                        .addAll(set));
        var mergedCards = new TreeSet<>(cards);
        mergedCards.addAll(other.cards);
        var mergedSpecials = EnumSet.noneOf(SpecialAction.class);
        mergedSpecials.addAll(specials);
        mergedSpecials.addAll(other.specials);
        var mergedModes = EnumSet.noneOf(SpecialAction.class);
        mergedModes.addAll(declarationModes);
        mergedModes.addAll(other.declarationModes);
        var mergedParadoxes = EnumSet.noneOf(ParadoxType.class);
        mergedParadoxes.addAll(paradoxTypes);
        mergedParadoxes.addAll(other.paradoxTypes);
        var mergedReasons = new TreeMap<Faction, SortedSet<String>>();
        scoreReasons.forEach((faction, set) -> mergedReasons
                .computeIfAbsent(faction, ignored -> new TreeSet<>())
                .addAll(set));
        other.scoreReasons.forEach((faction, set) -> mergedReasons
                .computeIfAbsent(faction, ignored -> new TreeSet<>())
                .addAll(set));
        var mergedPaths = EnumSet.noneOf(WinPath.class);
        mergedPaths.addAll(winPaths);
        mergedPaths.addAll(other.winPaths);
        return new Vocabulary(
                factions,
                mergedCards,
                mergedSpecials,
                mergedModes,
                mergedParadoxes,
                mergedReasons,
                mergedPaths,
                diagnostics || other.diagnostics,
                betSurvival || other.betSurvival);
    }

    public Set<Faction> factions(int playerCount) {
        return factionsByPlayerCount.getOrDefault(playerCount, Set.of());
    }
}
