package io.github.temporalrift.workbench.analysis.application.query;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import io.github.temporalrift.workbench.analysis.domain.scoring.BetOutcome;
import io.github.temporalrift.workbench.analysis.domain.scoring.BetRates;
import io.github.temporalrift.workbench.analysis.domain.scoring.FactionScoring;
import io.github.temporalrift.workbench.analysis.domain.scoring.InvalidScoringModelException;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringEngine;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringModel;
import io.github.temporalrift.workbench.shared.game.Faction;

/** Checks that a scoring model is inside the values the operation accepts, naming the first offending value. */
final class ScoringModelValidator {

    static final int DEFAULT_GAMES = 20_000;
    private static final int MIN_GAMES = 1_000;
    private static final int MAX_GAMES = 200_000;
    private static final int MIN_PLAYERS = 3;
    private static final int MAX_PLAYERS = 5;
    private static final String RATES = "rates";
    private static final int MAX_POINTS = 50;
    private static final int MAX_BONUS = 100;
    private static final int MAX_THRESHOLD = 500;
    private static final int MAX_ERAS = 10;
    private static final int MAX_WIN_POINTS = 10;
    private static final double RATE_TOLERANCE = 1e-9;

    private ScoringModelValidator() {}

    static void check(ScoringModel model) {
        checkGames(model.gamesPerPlayerCount());
        checkEngine(model.engine());
        checkPlayerCounts(model);
        checkRates(model);
    }

    static void checkGames(int games) {
        if (games < MIN_GAMES || games > MAX_GAMES) {
            throw invalid("gamesPerPlayerCount", "gamesPerPlayerCount must be " + between(MIN_GAMES, MAX_GAMES));
        }
    }

    private static void checkEngine(ScoringEngine engine) {
        if (engine.winThreshold() < 1 || engine.winThreshold() > MAX_THRESHOLD) {
            throw invalid("engine.winThreshold", "winThreshold must be between 1 and " + MAX_THRESHOLD);
        }
        if (engine.maxEras() < 1 || engine.maxEras() > MAX_ERAS) {
            throw invalid("engine.maxEras", "maxEras must be between 1 and " + MAX_ERAS);
        }
        if (engine.factions().isEmpty() || engine.factions().size() > Faction.values().length) {
            throw invalid("engine.factions", "factions must hold between 1 and " + Faction.values().length + " items");
        }
        var seen = new HashSet<Faction>();
        for (var index = 0; index < engine.factions().size(); index++) {
            var faction = engine.factions().get(index);
            if (!seen.add(faction.faction())) {
                throw invalid("engine.factions[" + index + "].faction", "each faction may appear only once");
            }
            checkFaction("engine.factions[" + index + "]", faction);
        }
    }

    private static void checkFaction(String path, FactionScoring faction) {
        var winPoints = faction.winPoints();
        if (winPoints.isEmpty() || winPoints.size() > MAX_WIN_POINTS) {
            throw invalid(path + ".winPoints", "winPoints must hold between 1 and " + MAX_WIN_POINTS + " items");
        }
        for (var index = 0; index < winPoints.size(); index++) {
            if (winPoints.get(index) < -MAX_POINTS || winPoints.get(index) > MAX_POINTS) {
                throw invalid(path + ".winPoints[" + index + "]", "a win scores " + between(-MAX_POINTS, MAX_POINTS));
            }
        }
        if (faction.missPoints() > 0 || faction.missPoints() < -MAX_POINTS) {
            throw invalid(path + ".missPoints", "missPoints must be " + between(-MAX_POINTS, 0));
        }
        if (faction.resetOn().contains(BetOutcome.WON)) {
            throw invalid(path + ".resetOn", "a win cannot reset the win run");
        }
        var wins = faction.objectiveWins();
        if (wins != null && (wins < 1 || wins > MAX_WIN_POINTS)) {
            throw invalid(path + ".objectiveWins", "objectiveWins must be between 1 and " + MAX_WIN_POINTS);
        }
        if (faction.objectiveBonus() < 0 || faction.objectiveBonus() > MAX_BONUS) {
            throw invalid(path + ".objectiveBonus", "objectiveBonus must be between 0 and " + MAX_BONUS);
        }
    }

    private static void checkPlayerCounts(ScoringModel model) {
        var counts = model.playerCounts();
        if (counts.isEmpty()) {
            throw invalid("playerCounts", "playerCounts must hold at least one item");
        }
        var seen = new HashSet<Integer>();
        for (var index = 0; index < counts.size(); index++) {
            var count = counts.get(index);
            var path = "playerCounts[" + index + "]";
            if (count < MIN_PLAYERS || count > MAX_PLAYERS) {
                throw invalid(path, "a player count must be " + between(MIN_PLAYERS, MAX_PLAYERS));
            }
            if (!seen.add(count)) {
                throw invalid(path, "each player count may appear only once");
            }
            if (count > model.engine().factions().size()) {
                throw invalid("playerCounts", "the engine has fewer factions than " + count + " players need");
            }
        }
    }

    private static void checkRates(ScoringModel model) {
        var wanted = new HashSet<Map.Entry<Integer, Faction>>();
        model.playerCounts()
                .forEach(count ->
                        model.engine().factions().forEach(faction -> wanted.add(Map.entry(count, faction.faction()))));
        var given = new HashSet<Map.Entry<Integer, Faction>>();
        var rates = model.rates();
        for (var index = 0; index < rates.size(); index++) {
            var item = rates.get(index);
            var path = "rates[" + index + "]";
            checkRateValues(path, item);
            var key = Map.entry(item.playerCount(), item.faction());
            if (!wanted.contains(key)) {
                throw invalid(RATES, "the rates match no requested player count and engine faction");
            }
            if (!given.add(key)) {
                throw invalid(RATES, "the rates repeat a player count and faction");
            }
        }
        if (given.size() != wanted.size()) {
            var missing = new ArrayList<>(wanted);
            missing.removeAll(given);
            throw invalid(RATES, "missing rates for " + describe(missing));
        }
    }

    private static void checkRateValues(String path, BetRates item) {
        if (!inUnit(item.winRate()) || !inUnit(item.missRate())) {
            throw invalid(path, "winRate and missRate must be between 0 and 1");
        }
        if (item.winRate() + item.missRate() > 1 + RATE_TOLERANCE) {
            throw invalid(path, "winRate + missRate must not exceed 1");
        }
        if (Double.isNaN(item.extraPointsPerEra()) || Math.abs(item.extraPointsPerEra()) > MAX_POINTS) {
            throw invalid(path, "extraPointsPerEra must be " + between(-MAX_POINTS, MAX_POINTS));
        }
    }

    private static String between(int low, int high) {
        return "between " + low + " and " + high;
    }

    private static boolean inUnit(double rate) {
        return rate >= 0 && rate <= 1;
    }

    private static String describe(List<Map.Entry<Integer, Faction>> missing) {
        return missing.stream()
                .sorted(Map.Entry.<Integer, Faction>comparingByKey()
                        .thenComparing(entry -> entry.getValue().name()))
                .map(entry -> "(" + entry.getKey() + ", " + entry.getValue() + ")")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    static InvalidScoringModelException invalid(String field, String message) {
        return new InvalidScoringModelException(field, message);
    }
}
