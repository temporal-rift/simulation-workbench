package io.github.temporalrift.workbench.analysis.application.query;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.HuntTallies;
import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.port.out.RunSource;
import io.github.temporalrift.workbench.shared.execution.CaseState;
import io.github.temporalrift.workbench.shared.game.Faction;

/** Counts the wins of one faction over the succeeded games of a table, straight from the run's recorded outcomes. */
public class HuntTalliesService implements HuntTallies {

    private final RunSource runs;

    public HuntTalliesService(RunSource runs) {
        this.runs = runs;
    }

    @Override
    public Tally tally(UUID runId, String tableId, Faction faction, List<String> seeds) {
        if (runs.experimentOf(runId).isEmpty()) {
            throw new IllegalArgumentException("Unknown run " + runId);
        }
        var wanted = new HashSet<>(seeds);
        var games = 0;
        var wins = 0;
        var playerCount = 0;
        for (var played : runs.cases(runId)) {
            if (!played.tableId().equals(tableId)
                    || played.status() != CaseState.SUCCEEDED
                    || played.outcome() == null
                    || (!wanted.isEmpty() && !wanted.contains(played.seed()))) {
                continue;
            }
            var seat = seatOf(played, faction);
            if (seat < 0) {
                continue;
            }
            playerCount = played.playerCount();
            games++;
            if (played.outcome().winnerSeats().contains(seat)) {
                wins++;
            }
        }
        return new Tally(playerCount, games, wins);
    }

    private static int seatOf(AnalyzedCase played, Faction faction) {
        return played.seatFactions().indexOf(faction);
    }
}
