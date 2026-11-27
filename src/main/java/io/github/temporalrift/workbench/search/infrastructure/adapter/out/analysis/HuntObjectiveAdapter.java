package io.github.temporalrift.workbench.search.infrastructure.adapter.out.analysis;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.HuntTallies;
import io.github.temporalrift.workbench.search.domain.port.out.HuntObjective;
import io.github.temporalrift.workbench.shared.game.Faction;

/** Counts the wins of the hunted faction through the analysis module. */
public class HuntObjectiveAdapter implements HuntObjective {

    private final HuntTallies tallies;

    public HuntObjectiveAdapter(HuntTallies tallies) {
        this.tallies = tallies;
    }

    @Override
    public Tally tally(UUID runId, String tableId, Faction faction, List<String> seeds) {
        var tally = tallies.tally(runId, tableId, faction, seeds);
        var interval = tally.interval();
        return new Tally(tally.playerCount(), tally.games(), tally.wins(), interval[0], interval[1]);
    }
}
