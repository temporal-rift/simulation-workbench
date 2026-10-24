package io.github.temporalrift.workbench.execution.infrastructure.serialization;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;

/**
 * The canonical JSON text of what a seat observed. Keys are sorted, so equal observations have equal text
 * and two runs can be compared as strings; the window carries its kind so the text reads back as the same
 * observation.
 */
public final class ObservationJson {

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .addMixIn(DecisionWindow.class, WindowKinds.class)
            .build();

    private ObservationJson() {}

    /** The canonical text of the observation. */
    public static String of(EntitledObservation observation) {
        return write(toMap(observation), observation);
    }

    /** The observation as a JSON object, for a response that embeds it. */
    public static Map<String, Object> toMap(EntitledObservation observation) {
        try {
            return MAPPER.convertValue(observation, OBJECT);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(description(observation) + " cannot be converted", e);
        }
    }

    /**
     * @throws IllegalStateException when the text is not an observation written by {@link #of}
     */
    public static EntitledObservation parse(String text) {
        try {
            return MAPPER.readValue(text, EntitledObservation.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("A retained observation cannot be read as JSON", e);
        }
    }

    private static String write(Map<String, Object> tree, EntitledObservation observation) {
        try {
            return MAPPER.writeValueAsString(tree);
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize " + description(observation), e);
        }
    }

    private static String description(EntitledObservation observation) {
        return "the observation of seat " + observation.seatIndex();
    }

    /** Tells Jackson which window record a {@code kind} names. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = DecisionWindow.HandSelection.class, name = "HandSelection"),
        @JsonSubTypes.Type(value = DecisionWindow.Declaration.class, name = "Declaration"),
        @JsonSubTypes.Type(value = DecisionWindow.ActionRound.class, name = "ActionRound"),
        @JsonSubTypes.Type(value = DecisionWindow.ParadoxResolution.class, name = "ParadoxResolution"),
        @JsonSubTypes.Type(value = DecisionWindow.TerminalReadiness.class, name = "TerminalReadiness")
    })
    private interface WindowKinds {}
}
