package io.github.temporalrift.workbench.analysis.application.query;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

import io.github.temporalrift.workbench.analysis.domain.scoring.Escalation;
import io.github.temporalrift.workbench.analysis.domain.scoring.ReasonPoints;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringEngine;

/** Resolves the requested one-point moves before reading case data. */
final class RescoreSweep {
    private static final Pattern REASON = Pattern.compile("reasonPoints\\.(.{1,64})");
    private static final Pattern ESCALATION =
            Pattern.compile("escalations\\.([a-z][a-z0-9-]{0,31})\\.(base|increment)");

    record Move(String parameter, int direction, ScoringEngine engine) {}

    static List<Move> resolve(ScoringEngine engine, List<String> paths) {
        if (paths == null) {
            return List.of();
        }
        if (paths.isEmpty() || paths.size() > 24) {
            throw ScoringModelValidator.invalid("sweep", "sweep must contain between 1 and 24 parameters");
        }
        var seen = new HashSet<String>();
        var moves = new ArrayList<Move>();
        for (int index = 0; index < paths.size(); index++) {
            var path = paths.get(index);
            if (path == null || !seen.add(path)) {
                throw ScoringModelValidator.invalid("sweep[" + index + "]", "parameter is null or repeated");
            }
            var reason = REASON.matcher(path);
            var escalation = ESCALATION.matcher(path);
            if (reason.matches()
                    && engine.reasonPoints().stream()
                            .anyMatch(p -> p.scoreReason().equals(reason.group(1)))) {
                var value = engine.reasonPoints().stream()
                        .filter(p -> p.scoreReason().equals(reason.group(1)))
                        .findFirst()
                        .orElseThrow()
                        .points();
                for (int direction : new int[] {-1, 1}) {
                    var points = engine.reasonPoints().stream()
                            .map(p -> p.scoreReason().equals(reason.group(1))
                                    ? new ReasonPoints(p.scoreReason(), p.points() + direction)
                                    : p)
                            .toList();
                    moves.add(new Move(
                            path,
                            direction,
                            Math.abs(value + direction) > ScoringModelValidator.MAX_POINTS
                                    ? null
                                    : new ScoringEngine(engine.maxEras(), points, engine.escalations())));
                }
            } else if (escalation.matches()
                    && engine.escalations().stream().anyMatch(e -> e.name().equals(escalation.group(1)))) {
                var item = engine.escalations().stream()
                        .filter(e -> e.name().equals(escalation.group(1)))
                        .findFirst()
                        .orElseThrow();
                boolean base = escalation.group(2).equals("base");
                int value = base ? item.base() : item.increment();
                int limit = base ? ScoringModelValidator.MAX_POINTS : ScoringModelValidator.MAX_INCREMENT;
                for (int direction : new int[] {-1, 1}) {
                    var escalations = engine.escalations().stream()
                            .map(e -> e.name().equals(item.name())
                                    ? new Escalation(
                                            e.name(),
                                            e.scoreReasons(),
                                            e.base() + (base ? direction : 0),
                                            e.increment() + (base ? 0 : direction),
                                            e.resetOn())
                                    : e)
                            .toList();
                    moves.add(new Move(
                            path,
                            direction,
                            Math.abs(value + direction) > limit
                                    ? null
                                    : new ScoringEngine(engine.maxEras(), engine.reasonPoints(), escalations)));
                }
            } else {
                throw ScoringModelValidator.invalid("sweep[" + index + "]", "unknown or malformed parameter");
            }
        }
        return List.copyOf(moves);
    }

    private RescoreSweep() {}
}
