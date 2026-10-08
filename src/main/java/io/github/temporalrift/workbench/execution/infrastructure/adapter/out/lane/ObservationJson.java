package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;

/**
 * The canonical JSON text of what a seat observed and drew from. Keys are sorted, so equal observations
 * have equal text and two runs can be compared as strings.
 */
public final class ObservationJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private ObservationJson() {}

    public static String of(EntitledObservation observation) {
        try {
            var tree = (ObjectNode) MAPPER.valueToTree(observation);
            ((ObjectNode) tree.get("window"))
                    .put("kind", observation.window().getClass().getSimpleName());
            return MAPPER.writeValueAsString(MAPPER.treeToValue(tree, Object.class));
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize the observation of seat " + observation.seatIndex(), e);
        }
    }

    /** The coordinates of the policy entropy stream a decision drew from. */
    public static String entropy(String policySeed, int seatIndex, String windowKey, int drawIndex) {
        var coordinates = new LinkedHashMap<String, Object>();
        coordinates.put("stream", "policy-entropy/v1");
        coordinates.put("policySeed", policySeed);
        coordinates.put("seat", seatIndex);
        coordinates.put("window", windowKey);
        coordinates.put("drawIndex", drawIndex);
        return write(coordinates);
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

    public static Integer era(DecisionWindow window) {
        return switch (window) {
            case DecisionWindow.HandSelection w -> w.era();
            case DecisionWindow.Declaration w -> w.era();
            case DecisionWindow.ActionRound w -> w.era();
            case DecisionWindow.ParadoxResolution w -> w.era();
            case DecisionWindow.TerminalReadiness w -> w.era();
        };
    }

    public static Integer round(DecisionWindow window) {
        return window instanceof DecisionWindow.ActionRound round ? round.round() : null;
    }

    private static String write(Map<String, Object> value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize evidence", e);
        }
    }
}
