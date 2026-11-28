package io.github.temporalrift.workbench.analysis.application.query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import io.github.temporalrift.workbench.analysis.domain.fact.BetReasons;
import io.github.temporalrift.workbench.analysis.domain.scoring.BetOutcome;
import io.github.temporalrift.workbench.analysis.domain.scoring.Escalation;
import io.github.temporalrift.workbench.analysis.domain.scoring.InvalidScoringModelException;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringEngine;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringModel;
import io.github.temporalrift.workbench.analysis.domain.scoring.SeatEraRates;
import io.github.temporalrift.workbench.shared.game.Faction;

/** Checks that a scoring model is inside the values the operation accepts, naming the first offending value. */
final class ScoringModelValidator {

    static final int DEFAULT_GAMES = 20_000;
    private static final int MIN_GAMES = 1_000;
    private static final int MAX_GAMES = 200_000;
    private static final int MIN_PLAYERS = 3;
    private static final int MAX_PLAYERS = 5;
    private static final String RATES = "rates";
    private static final String ENGINE = "engine";
    private static final String ITEMS = " items";
    private static final String SCORE_REASON = ".scoreReason";
    static final int MAX_POINTS = 50;
    static final int MAX_INCREMENT = 20;
    private static final int MAX_ERAS = 10;
    private static final int MAX_REASON_POINTS = 64;
    private static final int MAX_ESCALATIONS = 8;
    private static final double MAX_PER_ERA = 10;
    private static final double RATE_TOLERANCE = 1e-9;

    private ScoringModelValidator() {}

    static void check(ScoringModel model) {
        checkGames(model.gamesPerPlayerCount());
        checkEngine(model.engine());
        checkFactions(model);
        checkPlayerCounts(model);
        checkRates(model);
    }

    static void checkGames(int games) {
        if (games < MIN_GAMES || games > MAX_GAMES) {
            throw invalid("gamesPerPlayerCount", "gamesPerPlayerCount must be " + between(MIN_GAMES, MAX_GAMES));
        }
    }

    static void checkEngine(ScoringEngine engine) {
        if (engine.maxEras() < 1 || engine.maxEras() > MAX_ERAS) {
            throw invalid("engine.maxEras", "maxEras must be between 1 and " + MAX_ERAS);
        }
        var flat = checkReasonPoints(engine);
        checkEscalations(engine, flat);
    }

    private static Set<String> checkReasonPoints(ScoringEngine engine) {
        var points = engine.reasonPoints();
        if (points.isEmpty() || points.size() > MAX_REASON_POINTS) {
            throw invalid("engine.reasonPoints", "reasonPoints must hold between 1 and " + MAX_REASON_POINTS + ITEMS);
        }
        var seen = new HashSet<String>();
        for (var index = 0; index < points.size(); index++) {
            var path = "engine.reasonPoints[" + index + "]";
            var item = points.get(index);
            if (item.points() < -MAX_POINTS || item.points() > MAX_POINTS) {
                throw invalid(path + ".points", "points must be " + between(-MAX_POINTS, MAX_POINTS));
            }
            if (!seen.add(item.scoreReason())) {
                throw invalid(path + SCORE_REASON, item.scoreReason() + " is priced twice");
            }
        }
        return seen;
    }

    private static void checkEscalations(ScoringEngine engine, Set<String> flat) {
        var escalations = engine.escalations();
        if (escalations.size() > MAX_ESCALATIONS) {
            throw invalid("engine.escalations", "escalations must hold at most " + MAX_ESCALATIONS + ITEMS);
        }
        var names = new HashSet<String>();
        var escalated = new HashSet<String>();
        for (var index = 0; index < escalations.size(); index++) {
            var path = "engine.escalations[" + index + "]";
            var escalation = escalations.get(index);
            if (!names.add(escalation.name())) {
                throw invalid(path + ".name", "the escalation name " + escalation.name() + " is used twice");
            }
            checkEscalationValues(path, escalation);
            checkEscalatedReasons(path, escalation, flat, escalated);
        }
    }

    private static void checkEscalationValues(String path, Escalation escalation) {
        if (escalation.base() < -MAX_POINTS || escalation.base() > MAX_POINTS) {
            throw invalid(path + ".base", "base must be " + between(-MAX_POINTS, MAX_POINTS));
        }
        if (escalation.increment() < -MAX_INCREMENT || escalation.increment() > MAX_INCREMENT) {
            throw invalid(path + ".increment", "increment must be " + between(-MAX_INCREMENT, MAX_INCREMENT));
        }
        if (escalation.resetOn().isEmpty() || escalation.resetOn().contains(BetOutcome.WON)) {
            throw invalid(path + ".resetOn", "resetOn must hold MISSED, VOIDED or NOT_PLACED");
        }
    }

    private static void checkEscalatedReasons(
            String path, Escalation escalation, Set<String> flat, Set<String> escalated) {
        for (var reasonIndex = 0; reasonIndex < escalation.scoreReasons().size(); reasonIndex++) {
            var reason = escalation.scoreReasons().get(reasonIndex);
            var reasonPath = path + ".scoreReasons[" + reasonIndex + "]";
            if (flat.contains(reason)) {
                throw invalid(reasonPath, reason + " is both priced in reasonPoints and escalated");
            }
            if (!escalated.add(reason)) {
                throw invalid(reasonPath, reason + " belongs to two escalations");
            }
        }
    }

    /**
     * Checks, before any case is read, that the engine prices exactly the reasons the run's variant configures: a
     * configured reason the engine leaves unpriced is refused naming every such reason, and a priced reason the
     * variant does not configure is refused naming its item.
     */
    static void checkCoverage(ScoringEngine engine, Set<String> configured) {
        var priced = new HashSet<String>();
        for (var index = 0; index < engine.reasonPoints().size(); index++) {
            var reason = engine.reasonPoints().get(index).scoreReason();
            if (!configured.contains(reason)) {
                throw invalid(
                        "engine.reasonPoints[" + index + "].scoreReason",
                        "the run's variant configures no points for " + reason);
            }
            priced.add(reason);
        }
        for (var index = 0; index < engine.escalations().size(); index++) {
            var reasons = engine.escalations().get(index).scoreReasons();
            for (var reasonIndex = 0; reasonIndex < reasons.size(); reasonIndex++) {
                if (!configured.contains(reasons.get(reasonIndex))) {
                    throw invalid(
                            "engine.escalations[" + index + "].scoreReasons[" + reasonIndex + "]",
                            "the run's variant configures no points for " + reasons.get(reasonIndex));
                }
                priced.add(reasons.get(reasonIndex));
            }
        }
        var unpriced = new TreeSet<>(configured);
        unpriced.removeAll(priced);
        if (!unpriced.isEmpty()) {
            throw invalid(ENGINE, "the engine leaves unpriced: " + String.join(", ", unpriced));
        }
    }

    private static void checkFactions(ScoringModel model) {
        var factions = model.factions();
        if (factions.isEmpty() || factions.size() > Faction.values().length) {
            throw invalid("factions", "factions must hold between 1 and " + Faction.values().length + ITEMS);
        }
        if (new HashSet<>(factions).size() != factions.size()) {
            throw invalid("factions", "each faction may appear only once");
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
            if (count > model.factions().size()) {
                throw invalid("playerCounts", "the model has fewer factions than " + count + " players need");
            }
        }
    }

    private static void checkRates(ScoringModel model) {
        var wanted = new HashSet<Map.Entry<Integer, Faction>>();
        model.playerCounts()
                .forEach(count -> model.factions().forEach(faction -> wanted.add(Map.entry(count, faction))));
        var pricing = new HashMap<String, Boolean>();
        model.engine().reasonPoints().forEach(item -> pricing.put(item.scoreReason(), false));
        model.engine()
                .escalations()
                .forEach(escalation -> escalation.scoreReasons().forEach(reason -> pricing.put(reason, true)));
        var given = new HashSet<Map.Entry<Integer, Faction>>();
        var rates = model.rates();
        for (var index = 0; index < rates.size(); index++) {
            var item = rates.get(index);
            var path = RATES + "[" + index + "]";
            checkRateValues(path, item, pricing);
            var key = Map.entry(item.playerCount(), item.faction());
            if (!wanted.contains(key)) {
                throw invalid(RATES, "the rates match no requested player count and faction");
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

    /** @param pricing each priced reason, mapped to whether its points escalate */
    private static void checkRateValues(String path, SeatEraRates item, Map<String, Boolean> pricing) {
        var bet = BetReasons.of(item.faction());
        var seen = new HashSet<String>();
        var total = item.voidedRate();
        if (!inUnit(item.voidedRate())) {
            throw invalid(path + ".voidedRate", "voidedRate must be between 0 and 1");
        }
        for (var index = 0; index < item.betReasons().size(); index++) {
            var reason = item.betReasons().get(index);
            var reasonPath = path + ".betReasons[" + index + "]";
            if (!bet.isSettling(reason.scoreReason())) {
                throw invalid(
                        reasonPath + SCORE_REASON, reason.scoreReason() + " is not a bet reason of " + item.faction());
            }
            if (!pricing.containsKey(reason.scoreReason())) {
                throw invalid(reasonPath + SCORE_REASON, "the engine does not price " + reason.scoreReason());
            }
            if (!seen.add(reason.scoreReason())) {
                throw invalid(reasonPath + SCORE_REASON, reason.scoreReason() + " is listed twice");
            }
            if (!inUnit(reason.rate())) {
                throw invalid(reasonPath + ".rate", "rate must be between 0 and 1");
            }
            total += reason.rate();
        }
        if (total > 1 + RATE_TOLERANCE) {
            throw invalid(path, "the betReasons rates plus voidedRate must not exceed 1");
        }
        checkOtherReasons(path, item, pricing);
    }

    private static void checkOtherReasons(String path, SeatEraRates item, Map<String, Boolean> pricing) {
        var seen = new HashSet<String>();
        for (var index = 0; index < item.otherReasons().size(); index++) {
            var other = item.otherReasons().get(index);
            var reasonPath = path + ".otherReasons[" + index + "]";
            if (BetReasons.isBetReason(other.scoreReason())) {
                throw invalid(reasonPath + SCORE_REASON, other.scoreReason() + " settles a faction's bet");
            }
            var escalates = pricing.get(other.scoreReason());
            if (escalates == null) {
                throw invalid(reasonPath + SCORE_REASON, "the engine does not price " + other.scoreReason());
            }
            if (escalates) {
                throw invalid(
                        reasonPath + SCORE_REASON,
                        other.scoreReason() + " belongs to an escalation, which an expected count cannot escalate");
            }
            if (!seen.add(other.scoreReason())) {
                throw invalid(reasonPath + SCORE_REASON, other.scoreReason() + " is listed twice");
            }
            if (Double.isNaN(other.perEra()) || other.perEra() < 0 || other.perEra() > MAX_PER_ERA) {
                throw invalid(reasonPath + ".perEra", "perEra must be between 0 and 10");
            }
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
                .collect(Collectors.joining(", "));
    }

    static InvalidScoringModelException invalid(String field, String message) {
        return new InvalidScoringModelException(field, message);
    }
}
