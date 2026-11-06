package io.github.temporalrift.workbench.analysis.domain;

import java.util.Collections;
import java.util.List;

import io.github.temporalrift.workbench.analysis.domain.game.SeatPolicy;

/** The seat policies of a table in which every seat plays one policy. */
public final class SeatPolicies {

    private SeatPolicies() {}

    public static List<SeatPolicy> uniform(String id, String version, int players) {
        return Collections.nCopies(players, new SeatPolicy(id, version));
    }
}
