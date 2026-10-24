package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.util.LinkedHashMap;

import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.shared.text.JsonText;

/**
 * The canonical JSON text of what a seat observed and drew from. Keys are sorted, so equal observations
 * have equal text and two runs can be compared as strings.
 */
public final class ObservationJson {

    private ObservationJson() {}

    public static String of(EntitledObservation observation) {
        var description = "the observation of seat " + observation.seatIndex();
        var tree = (ObjectNode) JsonText.tree(observation, description);
        ((ObjectNode) tree.get("window"))
                .put("kind", observation.window().getClass().getSimpleName());
        return JsonText.canonical(tree, description);
    }

    /** The coordinates of the policy entropy stream a decision drew from. */
    public static String entropy(String policySeed, int seatIndex, String windowKey, int drawIndex) {
        var coordinates = new LinkedHashMap<String, Object>();
        coordinates.put("stream", "policy-entropy/v1");
        coordinates.put("policySeed", policySeed);
        coordinates.put("seat", seatIndex);
        coordinates.put("window", windowKey);
        coordinates.put("drawIndex", drawIndex);
        return JsonText.write(coordinates, "evidence");
    }

    /** The decision window's kind as the replay reports it. */
    public static String phase(DecisionWindow window) {
        return switch (window) {
            case DecisionWindow.HandSelection _ -> "HAND_SELECTION";
            case DecisionWindow.Declaration _ -> "DECLARATION";
            case DecisionWindow.ActionRound _ -> "ACTION_ROUND";
            case DecisionWindow.ParadoxResolution _ -> "PARADOX_RESOLUTION";
            case DecisionWindow.TerminalReadiness _ -> "TERMINAL_READINESS";
        };
    }

    public static Integer round(DecisionWindow window) {
        return window instanceof DecisionWindow.ActionRound round ? round.round() : null;
    }
}
